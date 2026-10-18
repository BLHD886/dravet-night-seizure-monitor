'use strict';
let token = '', database = null, syncing = false, refreshing = false;
let csvPreviewBody = null;
const $ = id => document.getElementById(id);
const showStatus = text => { $('status').textContent = text; };
const time = stamp => new Date(stamp).toLocaleString('zh-CN', {hour12:false});
const uid = () => {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6]=(bytes[6]&15)|64; bytes[8]=(bytes[8]&63)|128;
  const h=[...bytes].map(x=>x.toString(16).padStart(2,'0')).join('');
  return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}`;
};
async function api(path, body) {
  if (!token) throw new Error('请先连接开发服务');
  const response = await fetch(path, {method:body===undefined?'GET':'POST', headers:{'Authorization':'Bearer '+token,...(body===undefined?{}:{'Content-Type':'application/json'})}, body:body===undefined?undefined:JSON.stringify(body)});
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || '请求失败');
  return data;
}
function local(action, store, key, value) {
  return new Promise((resolve,reject)=>{
    if(!database) return reject(new Error('手机本地存储不可用'));
    const tx=database.transaction(store,action==='get'?'readonly':'readwrite');
    const object=tx.objectStore(store);
    const req=action==='get'?object.get(key):action==='put'?object.put(value,key):object.delete(key);
    tx.oncomplete=()=>resolve(req.result); tx.onerror=()=>reject(tx.error); tx.onabort=()=>reject(tx.error);
  });
}
async function initLocal() {
  try {
    database=await new Promise((resolve,reject)=>{const request=indexedDB.open('dravet-development-v1',1);request.onupgradeneeded=()=>{request.result.createObjectStore('cache');request.result.createObjectStore('outbox');};request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);});
    $('localState').textContent='手机本地：IndexedDB 已就绪（浏览器数据，卸载/清理后可能删除）';
    const cached=await local('get','cache','snapshot');
    if(cached){render(cached);showStatus('显示本机缓存 · '+time(cached.server_time)+'，不是实时状态');}
  } catch(error){$('localState').textContent='手机本地存储失败：'+error.message;}
}
function node(tag,text,className){const el=document.createElement(tag);if(text!==undefined)el.textContent=text;if(className)el.className=className;return el;}
function render(data){
  $('lastUpdate').textContent='服务端查询：'+time(data.server_time)+' · 未接入真实设备';
  const hr=data.samples.find(s=>s.sensor_type==='watch_hr');
  const radar=data.samples.find(s=>s.sensor_type==='radar_area');
  $('hr').textContent=hr?hr.payload.value+' bpm':'—';
  $('hrTime').textContent=hr?'模拟 · '+time(hr.observed_at)+' · 质量未知':'尚无腕表样本';
  $('radar').textContent=radar?'区域 0：'+(radar.payload.areas[0]?'有人':'无人'):'—';
  $('radarTime').textContent=radar?'模拟 · '+time(radar.observed_at)+' · 非孩子身份识别':'尚无雷达样本';
  $('aiState').textContent=data.ai_configured?'服务端已配置模型接口；本轮未验证模型可用性':'真实模型未配置；可先看本地摘要';
  $('importList').replaceChildren();
  for(const imported of (data.imports || [])){
    const card=node('div',undefined,'event');
    card.append(node('strong','CSV 回放 · '+imported.rows+' 行'),node('p',time(imported.window_from)+' — '+time(imported.window_to)),node('p',`脉搏 ${imported.sensor_counts.watch_hr} / ACC ${imported.sensor_counts.watch_acc} / 雷达 ${imported.sensor_counts.radar_area}；原始类别 ${imported.origin_kind}`));
    $('importList').append(card);
  }
  const latest=(data.imports || [])[0];
  $('accState').textContent=latest && latest.sensor_counts.watch_acc?'ACC：CSV 中存在样本，设备实时采集能力仍未验证；未启用运动算法':'ACC：未提供；依赖 ACC 的运动/节律分析停用，其余功能可用';
  $('eventList').replaceChildren();
  for(const event of data.events){
    const card=node('div',undefined,'event level'+event.level);
    card.append(node('strong',`${event.level===1?'一级 · 低打扰':'二级 · 强提醒'}（演练）`),node('p',time(event.created_at)+' · '+event.reason));
    const receipts=data.receipts.filter(r=>r.event_id===event.id);
    card.append(node('p','回执 / 人工标签：'+(receipts.map(r=>({viewed:'已查看',acknowledged:'已接手',confirmed_by_parent:'家长确认',false_alarm:'误报',uncertain:'不确定'})[r.status]).join('、')||'尚无')));
    for(const [status,label] of [['viewed','我已查看'],['acknowledged','我来接手'],['confirmed_by_parent','家长确认'],['false_alarm','误报'],['uncertain','不确定']]){
      const b=node('button',label);b.onclick=()=>run(async()=>{await api('/v1/receipts',{event_id:event.id,status});await refresh();});card.append(b);
    }
    $('eventList').append(card);
  }
  if(!data.events.length)$('eventList').append(node('p','尚无演练事件；没有事件不代表安全。'));
  $('observationList').replaceChildren();
  for(const record of data.observations){const card=node('div',undefined,'event');card.append(node('small','发生：'+time(record.occurred_at)+' / 记录：'+time(record.recorded_at)),node('p',record.text));$('observationList').append(card);}
}
async function refresh(){
  if(refreshing || !token)return;
  refreshing=true;
  try{const data=await api('/v1/snapshot');render(data);if(database)await local('put','cache','snapshot',data);showStatus('已连接 · 演示服务 · 仅网页前台查看，未接锁屏推送');}
  finally{refreshing=false;}
}
async function run(task){try{await task();}catch(error){showStatus(error.message);}}
async function submitPending(){
  if(syncing)return;syncing=true;
  try{const records=await new Promise((resolve,reject)=>{const req=database.transaction('outbox').objectStore('outbox').getAll();req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);});
    for(const record of records){await api('/v1/observations',record);await local('delete','outbox',record.id);}
  }finally{syncing=false;}
}
$('loginForm').onsubmit=e=>{e.preventDefault();run(async()=>{token=$('token').value.trim();await refresh();$('token').value='';if(database)await submitPending();await refresh();});};
document.querySelectorAll('[data-tab]').forEach(button=>button.onclick=()=>{document.querySelectorAll('.tab').forEach(s=>s.hidden=s.id!==button.dataset.tab);document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('active',b===button));});
document.querySelector('[data-tab]').classList.add('active');
$('tick').onclick=()=>run(async()=>{await api('/v1/demo/tick',{});await refresh();});
for(const level of [1,2])$('level'+level).onclick=()=>run(async()=>{await api('/v1/events',{level});await refresh();});
const d=new Date();$('occurred').value=new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,16);
$('observationForm').onsubmit=e=>{e.preventDefault();run(async()=>{const record={id:uid(),text:$('observation').value.trim(),occurred_at:new Date($('occurred').value).toISOString()};if(!record.text)return;await local('put','outbox',record.id,record);showStatus('已保存在手机，正在提交');await submitPending();$('observation').value='';await refresh();});};
function csvBody(){return {csv_text:$('csvText').value,column_map:$('csvMapping').value.trim()?JSON.parse($('csvMapping').value):null,timestamp_format:$('csvTimeFormat').value,timezone_offset:$('csvTimezone').value.trim()||null,origin_kind:$('csvOrigin').value};}
function invalidatePreview(){csvPreviewBody=null;$('csvImport').disabled=true;}
for(const id of ['csvText','csvMapping','csvTimeFormat','csvTimezone','csvOrigin'])$(id).addEventListener('input',invalidatePreview);
$('csvTemplate').onclick=()=>run(async()=>{const result=await api('/v1/csv/template');$('csvText').value=result.csv_text;$('csvOrigin').value='synthetic';$('csvTimeFormat').value='iso8601';$('csvTimezone').value='';$('csvMapping').value='';invalidatePreview();showStatus('已载入合成示例，先预览再确认导入');});
$('csvFile').onchange=()=>run(async()=>{const file=$('csvFile').files[0];if(!file)return;invalidatePreview();if(file.size>1000000)throw new Error('CSV 最大 1MB');$('csvText').value=new TextDecoder('utf-8',{fatal:true}).decode(await file.arrayBuffer());$('csvOrigin').value='unspecified';showStatus('已读取文件，请选择原始类别并预览');});
$('csvPreview').onclick=()=>run(async()=>{invalidatePreview();const body=csvBody();const result=await api('/v1/csv/preview',body);$('csvResult').textContent=JSON.stringify(result,null,2);if(!result.errors.length){csvPreviewBody=JSON.stringify(body);$('csvImport').disabled=false;showStatus('预览通过；ACC 缺失不会阻止导入');}else{showStatus('预览存在错误，请修正；尚未写入数据库');}});
$('csvImport').onclick=()=>run(async()=>{const body=csvBody();if(csvPreviewBody!==JSON.stringify(body))throw new Error('内容或设置变化，请重新预览');$('csvImport').disabled=true;try{const result=await api('/v1/csv/import',body);$('csvResult').textContent=JSON.stringify(result,null,2);invalidatePreview();await refresh();showStatus('CSV 已导入回放区，未触发实时报警');}catch(error){$('csvImport').disabled=false;throw error;}});
$('assistantForm').onsubmit=e=>{e.preventDefault();run(async()=>{$('answer').textContent='正在请求…';try{const result=await api('/v1/assistant',{question:$('question').value,consent:$('aiConsent').checked,scope:$('assistantScope').value});$('answer').textContent=(result.mode==='model'?'AI 模型回答 · '+result.model+'\n':'')+result.answer;}catch(error){$('answer').textContent='请求失败：'+error.message;throw error;}});};
initLocal();
setInterval(()=>{if(token && !document.hidden)run(refresh);},10000);
