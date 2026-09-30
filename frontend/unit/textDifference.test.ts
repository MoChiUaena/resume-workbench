import {test} from 'node:test';
import assert from 'node:assert/strict';
import {textDifference} from '../src/textDifference.ts';
import {addedNumericClaims,claimWarnings} from '../src/claimWarnings.ts';

const full=(parts:{text:string}[])=>parts.map(part=>part.text).join('');
const edits=(parts:{text:string;changed:boolean}[])=>parts.filter(part=>part.changed).map(part=>part.text);

test('separate Chinese edits retain the unchanged words between them',()=>{
 const before='参与接口设计，并参与联调。';
 const after='主导接口设计，并负责联调。';
 const difference=textDifference(before,after);
 assert.equal(full(difference.before),before);
 assert.equal(full(difference.after),after);
 assert.deepEqual(edits(difference.before),['参与','参与']);
 assert.deepEqual(edits(difference.after),['主导','负责']);
 assert(difference.before.some(part=>!part.changed&&part.text.includes('接口设计，并')));
});

test('word, punctuation and emoji boundaries preserve exact source text',()=>{
 for(const [before,after] of [
  ['🦖处理 Spring Boot 接口。','🦖优化 Spring Boot 接口。'],
  ['保持原文','保持原文'],
  ['A','AB'],
  ['记录问题，修复接口。','记录问题；修复接口。'],
  ['','新增内容']
 ]){
  const difference=textDifference(before,after);
  assert.equal(full(difference.before),before);
  assert.equal(full(difference.after),after);
 }
 const emoji=textDifference('🦖处理 Spring Boot 接口。','🦖优化 Spring Boot 接口。');
 assert.deepEqual(edits(emoji.before),['处理']);
 assert.deepEqual(edits(emoji.after),['优化']);
});

test('responsibility and outcome hints react to new claims, including repeated roles',()=>{
 assert.deepEqual(claimWarnings('参与联调。','主导联调并确保稳定。'),{roles:['主导'],outcomes:['确保']});
 assert.deepEqual(claimWarnings('负责测试，参与联调。','负责测试，负责联调。'),{roles:['负责'],outcomes:[]});
 assert.deepEqual(claimWarnings('负责测试并确保稳定。','负责测试并确保稳定。'),{roles:[],outcomes:[]});
 assert.deepEqual(claimWarnings('参与联调。','参与联调并记录问题。'),{roles:[],outcomes:[]});
});

test('numeric review hint follows the human-edited proposal rather than the first model reply',()=>{
 assert.equal(addedNumericClaims('完成接口核对。','完成接口核对，提升 30%。'),true);
 assert.equal(addedNumericClaims('完成接口核对。','完成接口核对。'),false);
 assert.equal(addedNumericClaims('处理 30% 的请求。','处理 30% 的请求并记录结果。'),false);
});
