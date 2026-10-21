const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('android/app/src/main/assets/app.html','utf8'),js=html.match(/<script>([\s\S]*?)<\/script>/)[1];new vm.Script(js);
let ctx={};vm.createContext(ctx);vm.runInContext(js.slice(js.indexOf('function defaultAlertSettings('),js.indexOf('function editorToggle(')),ctx);vm.runInContext(js.slice(js.indexOf('function validateRulePreview('),js.indexOf('// V8:')),ctx);
let c=ctx.defaultAlertSettings();ctx.validateRulePreview(c);let p=c.profiles.awake;
p.low.enabled=true;assert.throws(()=>ctx.validateRulePreview(c));p.low.threshold=100;ctx.validateRulePreview(c);
p.oxygen.enabled=true;p.oxygen.threshold=95;p.oxygen.hold_s=0;ctx.validateRulePreview(c);assert(ctx.metricRuleSummary(p).includes('单次'));
p.resp.enabled=true;p.resp.lower=20;p.resp.upper=10;assert.throws(()=>ctx.validateRulePreview(c));p.resp.upper=30;ctx.validateRulePreview(c);
p.resp.upper=3000;assert.throws(()=>ctx.validateRulePreview(c));
const ids=[...html.matchAll(/id="([^" ]+)"/g)].map(m=>m[1]);assert.equal(new Set(ids).size,ids.length,'duplicate DOM ids');
for(const id of ['savedRuleStatus','homeRuleSummary','trendRuleSummary','mineRuleSummary','ruleEditor'])assert(ids.includes(id),id);
assert.equal(c.profiles.sleep.high.l1,114);assert.equal(c.profiles.sleep.low.enabled,false,'editing awake does not alter sleep');

assert(js.includes("call('saveAlertSettings',{settings:ruleDraft})"));assert(js.includes('有未保存修改'));assert(js.includes('rule_snapshot'));
console.log('Adjustable UI checks passed: validation, pending/spot values, interval bounds, single DOM ids and shared-rule destinations');
