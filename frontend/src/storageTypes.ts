export type StorageItem={kind:'image'|'pdf'|'other';id:string|null;status:string;reason:string;bytes:number;lastModified:string|null};
export type StorageCount={key:string;count:number;bytes:number};
export type StorageReport={checkedAt:string;graceDays:number;referencesVerified:boolean;bytesComplete:boolean;kinds:StorageCount[];statuses:StorageCount[];items:StorageItem[];digest:string};
export type Selection={kind:'image'|'pdf';id:string};
export type QuarantineRequest={operationId:string;previewDigest:string;items:Selection[];confirm:true};
export type QuarantineReceipt={id:string;state:'preparing'|'moving'|'quarantined'|'restoring'|'restored'|'attention';createdAt:string;updatedAt:string;items:(Selection&{bytes:number})[];bytes:number;backupId:string|null;digest:string;errorCode:string|null};
export type QuarantineHistory={items:QuarantineReceipt[];page:number;hasMore:boolean;unreadable:number};
export function storageSize(bytes:number){return bytes<1024?`${bytes} B`:bytes<1048576?`${(bytes/1024).toFixed(1)} KiB`:`${(bytes/1048576).toFixed(2)} MiB`;}
export function storageDate(value:string|null){return value?new Date(value).toLocaleString('zh-CN'):'时间未知';}
export const quarantineStates:Record<QuarantineReceipt['state'],string>={preparing:'准备中',moving:'暂存中',quarantined:'已暂存',restoring:'恢复中',restored:'已恢复',attention:'需要恢复检查'};

const receiptUuid=/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const receiptDigest=/^[0-9a-f]{64}$/;
const receiptInstant=/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;
function receiptObject(value:unknown):value is Record<string,unknown>{return typeof value==='object'&&value!==null&&!Array.isArray(value);}
function receiptId(value:unknown):value is string{return typeof value==='string'&&receiptUuid.test(value);}
function receiptBytes(value:unknown):value is number{return typeof value==='number'&&Number.isSafeInteger(value)&&value>=0&&value<=1073741824;}
function receiptTime(value:unknown):value is string{return typeof value==='string'&&value.length<=64&&receiptInstant.test(value)&&Number.isFinite(Date.parse(value));}
/** An unreadable or unrelated acknowledgement cannot consume an immutable pending request. */
export function isQuarantineReceipt(value:unknown,expectedId:string):value is QuarantineReceipt{
 if(!receiptObject(value)||!receiptId(value.id)||value.id!==expectedId||typeof value.state!=='string'||!Object.hasOwn(quarantineStates,value.state))return false;
 if(!receiptTime(value.createdAt)||!receiptTime(value.updatedAt)||!receiptBytes(value.bytes)||typeof value.digest!=='string'||!receiptDigest.test(value.digest))return false;
 if(value.backupId!==null&&!receiptId(value.backupId))return false;
 if(value.errorCode!==null&&(typeof value.errorCode!=='string'||!/^[A-Z][A-Z0-9_]{0,79}$/.test(value.errorCode)))return false;
 if(!Array.isArray(value.items)||value.items.length===0||value.items.length>100)return false;
 const identities=new Set<string>();let total=0;
 for(const item of value.items){
  if(!receiptObject(item)||(item.kind!=='image'&&item.kind!=='pdf')||!receiptId(item.id)||!receiptBytes(item.bytes))return false;
  const identity=item.kind+':'+item.id;if(identities.has(identity))return false;identities.add(identity);total+=item.bytes;
 }
 return total===value.bytes;
}
