export type DifferencePart={text:string;changed:boolean};

function words(value:string):string[]{
 if(typeof Intl.Segmenter!=='function')return Array.from(value);
 return Array.from(new Intl.Segmenter('zh',{granularity:'word'}).segment(value),part=>part.segment);
}

function append(parts:DifferencePart[],text:string,changed:boolean){
 if(!text)return;
 const last=parts.at(-1);
 if(last?.changed===changed)last.text+=text;
 else parts.push({text,changed});
}

/** Compare whole words where possible so separate edits stay visible in both columns. */
export function textDifference(before:string,after:string):{before:DifferencePart[];after:DifferencePart[]}{
 if(before===after)return{before:[{text:before,changed:false}],after:[{text:after,changed:false}]};
 const a=words(before),b=words(after),width=b.length+1;
 const lengths=new Uint16Array((a.length+1)*width);
 for(let i=a.length-1;i>=0;i--)for(let j=b.length-1;j>=0;j--){
  const index=i*width+j;
  lengths[index]=a[i]===b[j]?lengths[(i+1)*width+j+1]+1:
   Math.max(lengths[(i+1)*width+j],lengths[i*width+j+1]);
 }
 const old:DifferencePart[]=[],proposal:DifferencePart[]=[];
 let i=0,j=0;
 while(i<a.length||j<b.length){
  if(i<a.length&&j<b.length&&a[i]===b[j]){
   append(old,a[i],false);append(proposal,b[j],false);i++;j++;
  }else if(i<a.length&&(j===b.length||lengths[(i+1)*width+j]>=lengths[i*width+j+1])){
   append(old,a[i++],true);
  }else append(proposal,b[j++],true);
 }
 return{before:old,after:proposal};
}
