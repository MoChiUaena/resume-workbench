import test from 'node:test';
import assert from 'node:assert/strict';
import {isQuarantineReceipt,isFileBackupReceipt} from '../src/storageTypes.ts';
const id='a1111111-1111-4111-8111-000000000001',exportId='b1111111-1111-4111-8111-000000000002';
const receipt=()=>({id,state:'purging',createdAt:'2026-10-01T00:00:00Z',updatedAt:'2026-10-01T00:00:00Z',items:[{kind:'image',id:exportId,bytes:10}],bytes:10,backupId:null,digest:'a'.repeat(64),errorCode:'QUARANTINE_PURGE_FAILED',cleanup:{exportId,sha256:'b'.repeat(64),bytes:200}});
test('purging and purged require durable bounded cleanup proof',()=>{
 for(const state of ['purging','purged']){const value={...receipt(),state,errorCode:state==='purged'?null:'QUARANTINE_PURGE_FAILED'};assert.equal(isQuarantineReceipt(value,id),true);for(const cleanup of [undefined,null,{}, {...receipt().cleanup,exportId:'../bad'},{...receipt().cleanup,sha256:'B'.repeat(64)},{...receipt().cleanup,bytes:1074790401}])assert.equal(isQuarantineReceipt({...value,cleanup},id),false);}
});
test('ordinary receipt cannot acquire purge proof',()=>{
 assert.equal(isQuarantineReceipt({...receipt(),state:'quarantined'},id),false);
 assert.equal(isQuarantineReceipt({...receipt(),state:'quarantined',cleanup:null},id),true);
});
const ticket=()=>({id:exportId,operationId:id,digest:'a'.repeat(64),bytes:200,sha256:'b'.repeat(64),createdAt:'2026-10-01T00:00:00Z',expiresAt:'2026-10-01T00:10:00Z',downloaded:false});
test('file backup receipt binds request, operation, token and all mandatory proof fields',()=>{
 const valid=ticket();assert.equal(isFileBackupReceipt(valid,exportId,id,valid.digest),true);assert.equal(isFileBackupReceipt({...valid,downloaded:true},exportId,id,valid.digest),true);
 for(const value of [null,{},[],{...valid,id},{...valid,operationId:exportId},{...valid,digest:'c'.repeat(64)}])assert.equal(isFileBackupReceipt(value,exportId,id,valid.digest),false);
 for(const field of Object.keys(valid)){const value:Record<string,unknown>={...valid};delete value[field];assert.equal(isFileBackupReceipt(value,exportId,id,valid.digest),false,field);}
});
test('file backup receipt rejects unsafe archive bounds and malformed status or times',()=>{
 const valid=ticket();for(const bytes of [0,-1,.5,1074790401,Infinity,'200'])assert.equal(isFileBackupReceipt({...valid,bytes},exportId,id,valid.digest),false);
 for(const sha256 of [null,'B'.repeat(64),'../path'])assert.equal(isFileBackupReceipt({...valid,sha256},exportId,id,valid.digest),false);
 for(const downloaded of [null,1,'true'])assert.equal(isFileBackupReceipt({...valid,downloaded},exportId,id,valid.digest),false);
 for(const expiresAt of [null,'not-time',valid.createdAt,'2026-09-01T00:10:00Z'])assert.equal(isFileBackupReceipt({...valid,expiresAt},exportId,id,valid.digest),false);
 assert.equal(isFileBackupReceipt({...valid,bytes:1074790400},exportId,id,valid.digest),true);
});
