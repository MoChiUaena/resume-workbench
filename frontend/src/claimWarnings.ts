const roleClaims=['负责','主导','牵头','统筹','独立完成'] as const;
const outcomeClaims=['确保','提升','降低','缩短','增长'] as const;
const count=(text:string,phrase:string)=>text.split(phrase).length-1;

/** Surface newly asserted responsibility or impact; it is a review hint, not fact checking. */
export function claimWarnings(before:string,after:string){
 const introduced=(phrases:readonly string[])=>phrases.filter(phrase=>count(after,phrase)>count(before,phrase));
 return{roles:introduced(roleClaims),outcomes:introduced(outcomeClaims)};
}
