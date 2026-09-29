// Isolated test fixture. No real credentials or resume records are used.
import http from 'node:http';
const requests=[];
http.createServer(async(req,res)=>{
 if(req.url==='/health'){res.writeHead(200,{'Content-Type':'application/json'});res.end('{"ok":true}');return;}
 if(req.url==='/requests'){res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(requests));return;}
 if(req.method!=='POST'||req.url!=='/v1/chat/completions'){res.writeHead(404);res.end();return;}
 let raw='';for await(const chunk of req){raw+=chunk;if(raw.length>65536){res.writeHead(413);res.end();return;}}
 const input=JSON.parse(raw);requests.push({model:input.model,messages:input.messages,tools:input.tools??null});
 if(input.model==='qa-error'){res.writeHead(401,{'Content-Type':'application/json'});res.end('{"error":{"message":"fixture rejection"}}');return;}
 const user=input.messages.find(message=>message.role==='user')?.content||'';
 const content=input.response_format?JSON.stringify({text:user+'（表述优化）'}):'OK';
 if(input.model==='qa-slow')await new Promise(resolve=>setTimeout(resolve,1800));
 res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({id:'fixture',object:'chat.completion',created:1,model:input.model,choices:[{index:0,message:{role:'assistant',content},finish_reason:'stop'}],usage:{prompt_tokens:10,completion_tokens:10,total_tokens:20}}));
}).listen(18770,'127.0.0.1');
