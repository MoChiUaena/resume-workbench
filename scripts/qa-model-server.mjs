// Isolated protocol fixture. No real credentials or resume records are used.
import http from 'node:http';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

export function startQaModelServer(port=18770){
 let expectedKeys;
 try{
  expectedKeys=JSON.parse(process.env.QA_MODEL_EXPECTED_KEYS||'{}');
  if(!expectedKeys||Array.isArray(expectedKeys)||typeof expectedKeys!=='object'||
     Object.values(expectedKeys).some(key=>typeof key!=='string'||!key))throw new Error();
 }catch{throw new Error('QA_MODEL_EXPECTED_KEYS must be a JSON object mapping models to nonempty synthetic keys');}
 const requests=[];
 const server=http.createServer(async(req,res)=>{
  if(req.url==='/health'){res.writeHead(200,{'Content-Type':'application/json'});res.end('{"ok":true}');return;}
  if(req.url==='/requests'){res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(requests));return;}
  if(req.method!=='POST'||req.url!=='/v1/chat/completions'){res.writeHead(404);res.end();return;}
  let raw='';for await(const chunk of req){raw+=chunk;if(raw.length>65536){res.writeHead(413);res.end();return;}}
  let input;try{input=JSON.parse(raw);}catch{res.writeHead(400);res.end();return;}
  if(Object.hasOwn(expectedKeys,input.model)&&req.headers.authorization!=='Bearer '+expectedKeys[input.model]){
   res.writeHead(401,{'Content-Type':'application/json'});res.end('{"error":{"message":"fixture authentication rejected"}}');return;
  }
  requests.push({model:input.model,messages:input.messages,tools:input.tools??null});
  if(input.model==='qa-error'||input.model==='qa-job-error'){res.writeHead(401,{'Content-Type':'application/json'});res.end('{"error":{"message":"fixture rejection"}}');return;}
  const user=input.messages.find(message=>message.role==='user')?.content||'';
  let job;try{const candidate=JSON.parse(user);if(typeof candidate.jobDescription==='string'&&Array.isArray(candidate.sources))job=candidate;}catch{}
  let content;
  if(job){
   const requirements=job.jobDescription.split(/[\n；;]+/).map(text=>text.trim()).filter(Boolean).slice(0,12);
   const first=job.sources.find(source=>typeof source.text==='string'&&source.text.trim())||job.sources[0];
   const body=job.sources.find(source=>typeof source.text==='string'&&source.text.trim()&&source.text!==first?.text)||first;
   const quote=first?.text?.slice(0,Math.min(16,first.text.length))||'';
   const partialQuote=body?.text?.slice(0,Math.min(16,body.text.length))||quote;
   const items=requirements.map((requirement,index)=>({
    requirement,status:index===0?'supported':index===1?'partial':'missing',
    evidence:index>1?[]:[{sourceId:index===0?first.id:body.id,quote:index===0?quote:partialQuote}],
    advice:index>1?'请根据真实经历补充证据；没有相关经历可保留缺项。':'请核对引用与岗位要求是否真正相关。'
   }));
   // The production payload intentionally omits local paragraph metadata. Only known synthetic
   // body markers are eligible; every other source, including headings, stays read-only here.
   const suggestions=job.sources.filter(source=>/^(?:PROJECT|SECOND)-BODY-CANARY/.test(source.text)&&source.text.length<=780)
    .slice(0,6).map(source=>({sourceId:source.id,replacement:source.text+'（请人工核对表述）'}));
   content=JSON.stringify({items,suggestions});
   if(input.model==='qa-job-bad-reference')content=JSON.stringify({items:[{requirement:requirements[0],status:'supported',evidence:[{sourceId:'s999',quote:'fabricated'}],advice:''}],suggestions:[]});
  }else content=input.response_format?JSON.stringify({text:user+'（表述优化）'}):'OK';
  if(input.model==='qa-slow'||input.model==='qa-job-slow')await new Promise(resolve=>setTimeout(resolve,1800));
  if(res.destroyed)return;
  res.writeHead(200,{'Content-Type':'application/json'});
  res.end(JSON.stringify({id:'fixture',object:'chat.completion',created:1,model:input.model,choices:[{index:0,message:{role:'assistant',content},finish_reason:'stop'}],usage:{prompt_tokens:10,completion_tokens:10,total_tokens:20}}));
 });
 server.listen(port,'127.0.0.1');
 return server;
}

if(process.argv[1]&&fileURLToPath(import.meta.url)===path.resolve(process.argv[1]))startQaModelServer();
