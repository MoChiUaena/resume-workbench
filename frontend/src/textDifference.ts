/** Highlight the changed span without splitting emoji or interpreting HTML. */
export function textDifference(before:string,after:string){
 const a=Array.from(before),b=Array.from(after);let prefix=0,suffix=0;
 while(prefix<a.length&&prefix<b.length&&a[prefix]===b[prefix])prefix++;
 while(suffix<a.length-prefix&&suffix<b.length-prefix&&a[a.length-1-suffix]===b[b.length-1-suffix])suffix++;
 return{prefix:a.slice(0,prefix).join(''),before:a.slice(prefix,a.length-suffix).join(''),after:b.slice(prefix,b.length-suffix).join(''),suffix:a.slice(a.length-suffix).join('')};
}
