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
