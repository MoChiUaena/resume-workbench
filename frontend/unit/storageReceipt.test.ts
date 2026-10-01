import test from 'node:test';
import assert from 'node:assert/strict';
import {isQuarantineReceipt} from '../src/storageTypes.ts';
const operation='a1111111-1111-4111-8111-000000000001',item='b1111111-1111-4111-8111-000000000002';
const receipt=()=>({id:operation,state:'quarantined',createdAt:'2026-10-01T00:00:00Z',updatedAt:'2026-10-01T00:00:00.123456789Z',items:[{kind:'image',id:item,bytes:1024}],bytes:1024,backupId:null,digest:'a'.repeat(64),errorCode:null});
test('all actual receipt states and nullable metadata are accepted without losing identity',()=>{
 for(const state of ['preparing','moving','quarantined','restoring','restored','attention'])assert.equal(isQuarantineReceipt({...receipt(),state,backupId:operation,errorCode:state==='attention'?'QUARANTINE_IO_FAILED':null},operation),true);
});
test('receipt must match the requested UUID operation',()=>{
 for(const id of [item,'not-a-uuid',operation.toUpperCase(),null])assert.equal(isQuarantineReceipt({...receipt(),id},operation),false);
});
test('nonobjects and receipts missing any required field stay uncertain',()=>{
 for(const value of [null,{},[],1,'receipt'])assert.equal(isQuarantineReceipt(value,operation),false);
 for(const field of Object.keys(receipt())){const value:Record<string,unknown>=receipt();delete value[field];assert.equal(isQuarantineReceipt(value,operation),false,field);}
});
test('unsupported states and malformed recovery tokens are rejected',()=>{
 for(const state of ['deleted','toString','',null])assert.equal(isQuarantineReceipt({...receipt(),state},operation),false);
 for(const digest of ['','a'.repeat(63),'A'.repeat(64),'a'.repeat(65),null])assert.equal(isQuarantineReceipt({...receipt(),digest},operation),false);
});
test('receipt item list is nonempty, bounded and contains distinct valid image or PDF identities',()=>{
 for(const items of [null,{},[],Array(101).fill(receipt().items[0]),...[null,1,'other',['image'],{toString:'image'}].map(kind=>[{kind,id:item,bytes:1024}]),[{kind:'image',id:'../invalid',bytes:1024}],[null],[receipt().items[0],receipt().items[0]]])assert.equal(isQuarantineReceipt({...receipt(),items},operation),false);
 assert.equal(isQuarantineReceipt({...receipt(),items:[{kind:'pdf',id:item,bytes:1024}]},operation),true);
});
test('item and total byte counts are finite nonnegative integers and agree',()=>{
 for(const bytes of [-1,0.5,1073741825,Number.MAX_SAFE_INTEGER,Infinity,'1024',null]){assert.equal(isQuarantineReceipt({...receipt(),bytes},operation),false);assert.equal(isQuarantineReceipt({...receipt(),items:[{kind:'image',id:item,bytes}]},operation),false);}
 assert.equal(isQuarantineReceipt({...receipt(),bytes:1023},operation),false);
});
test('exact hundred item and one GiB limits are accepted',()=>{
 const items=Array.from({length:100},(_,index)=>({kind:'image',id:`22222222-2222-4222-8222-${String(index).padStart(12,'0')}`,bytes:index===0?1073741824:0}));assert.equal(isQuarantineReceipt({...receipt(),items,bytes:1073741824},operation),true);
});
test('backup and error fields are nullable but must be bounded valid identifiers when present',()=>{
 for(const backupId of ['',12,'../backup',operation+'x'])assert.equal(isQuarantineReceipt({...receipt(),backupId},operation),false);
 for(const errorCode of ['',12,'../private/path','A'.repeat(81)])assert.equal(isQuarantineReceipt({...receipt(),errorCode},operation),false);
});
test('receipt timestamps must be bounded parseable UTC instants',()=>{
 for(const field of ['createdAt','updatedAt'])for(const value of [null,12,'invalid','2026-10-01','2026-10-01T00:00:00Z'+'x'.repeat(100)])assert.equal(isQuarantineReceipt({...receipt(),[field]:value},operation),false);
});
