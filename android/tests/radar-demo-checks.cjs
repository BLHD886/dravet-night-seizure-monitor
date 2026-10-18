const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('android/app/src/main/assets/app.html','utf8'),script=html.match(/<script>([\s\S]*?)<\/script>/)[1];new vm.Script(script);
const fn=script.slice(script.indexOf('function radarDemoFrame('),script.indexOf('function demoPlot('));let ctx={};vm.createContext(ctx);vm.runInContext(fn,ctx);
assert.equal(ctx.radarDemoFrame(0).ready,false);assert.equal(ctx.radarDemoFrame(9999).ready,false);assert.equal(ctx.radarDemoFrame(10000,()=>0).value,14);assert.equal(ctx.radarDemoFrame(10001,()=>1).value,18);
const block=script.slice(script.indexOf('// UI-only synthetic radar presentation.'),script.indexOf('initV3();initV4();initV5();initRadarDemo();'));
for(const denied of ['call(', 'GuardNative', 'state.history', 'state.rule','localStorage','sessionStorage','fetch('])assert(!block.includes(denied),denied);
assert(!html.includes('电脑'));assert(!html.includes('LD6004'));assert(html.includes('未连接真实雷达'));assert(html.includes('模拟演示'));assert(html.includes('id="stopRadarDemo"'));
console.log('Radar demo: 10s boundary, bounded synthetic values, isolation, labels and text checks passed');
