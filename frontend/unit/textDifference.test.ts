import {test} from 'node:test';
import assert from 'node:assert/strict';
import {textDifference} from '../src/textDifference.ts';
test('changed span preserves identical prefixes, suffixes and unicode characters',()=>{
 assert.deepEqual(textDifference('实现事务处理','完善事务处理'),{prefix:'',before:'实现',after:'完善',suffix:'事务处理'});
 assert.deepEqual(textDifference('🦖处理接口','🦖优化接口'),{prefix:'🦖',before:'处理',after:'优化',suffix:'接口'});
 assert.deepEqual(textDifference('保持原文','保持原文'),{prefix:'保持原文',before:'',after:'',suffix:''});
 assert.deepEqual(textDifference('A','AB'),{prefix:'A',before:'',after:'B',suffix:''});
});
