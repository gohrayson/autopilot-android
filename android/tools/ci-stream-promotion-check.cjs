'use strict';
// Disposable hosted Linux emulator only. Synthetic outbound credentials, real TLS,
// production discovery + watchdog, continuous socket from a separate Android UID.
const fs=require('node:fs/promises'),path=require('node:path'),cp=require('node:child_process');
const execute=require('node:util').promisify(cp.execFile);
const project=path.resolve(process.argv[2]||path.join(__dirname,'..'));
const output=path.resolve(process.env.GITHUB_WORKSPACE||project,'ci-native-results');
const app='app.autopilot.android.debug';
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let serial,main,probe,log='',probeLog='',mainExited=false,probeExited=false;
async function adb(...args){return(await execute('adb',['-s',serial,...args],{timeout:90000,maxBuffer:8*1024*1024,windowsHide:true})).stdout;}
async function read(name){if(!/^[a-z0-9.-]+$/.test(name))throw Error('Invalid fixture filename');try{return await adb('shell','run-as',app,'cat','files/'+name);}catch{return'';}}
async function save(){if(!serial)return;for(const name of['stream-promotion-evidence.json','stream-promotion-fixture.json','diagnostics.log']){const text=await read(name);if(text)await fs.writeFile(path.join(output,name==='diagnostics.log'?'stream-promotion-diagnostics.log':name),text);}await fs.writeFile(path.join(output,'stream-promotion-instrumentation.log'),log);await fs.writeFile(path.join(output,'stream-promotion-other-uid.log'),probeLog);}
function launch(args,append,onExit){const child=cp.spawn('adb',['-s',serial,'shell','am','instrument','-w',...args]);child.stdout.on('data',b=>{append(String(b));process.stdout.write(b);});child.stderr.on('data',b=>{append(String(b));process.stderr.write(b);});const done=new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',code=>{onExit();resolve(code);});});done.catch(()=>{});return{child,done};}
async function bounded(promise,until,purpose){let timer;try{return await Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error(purpose+' exceeded hosted deadline')),Math.max(1,until-Date.now()));})]);}finally{clearTimeout(timer);}}
async function mainRun(){
  if(process.platform!=='linux'||process.env.GITHUB_ACTIONS!=='true')throw Error('Require disposable hosted GitHub Linux runner');
  const testSource=await fs.readFile(path.join(project,'app/src/androidTest/java/app/autopilot/android/StreamPromotionRuntimeTest.java'),'utf8');
  const testMethods=[...testSource.matchAll(/@Test(?:\s*\([^)]*\))?\s+public\s+void\s+(\w+)\s*\(/g)].map(match=>match[1]);
  if(JSON.stringify(testMethods)!==JSON.stringify(['expiredRankingPromotionPreservesOldSocketAndConfirmedFailureClosesIt']))throw Error('Stream instrumentation source catalog differs from the standalone runner');
  await fs.mkdir(output,{recursive:true});const devices=(await execute('adb',['devices'])).stdout.trim().split(/\r?\n/).slice(1).filter(Boolean);
  if(devices.length!==1||!/^emulator-\d+\s+device$/.test(devices[0]))throw Error('Require exactly one online emulator and no physical device');serial=devices[0].split(/\s/)[0];
  for(const file of['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk','probe/build/outputs/apk/debug/probe-debug.apk'])if(!(await adb('install','-r','-t',path.join(project,file))).includes('Success'))throw Error('Stream fixture APK install failed');
  if(app!=='app.autopilot.android.debug'||!/^emulator-\d+$/.test(serial))throw Error('Disposable clear guard failed');
  if(!(await adb('shell','pm','clear',app)).includes('Success'))throw Error('Disposable clear failed');
  await adb('shell','appops','set',app,'ACTIVATE_VPN','allow');await adb('shell','pm','grant',app,'android.permission.POST_NOTIFICATIONS');
  const started=launch(['-e','class','app.autopilot.android.StreamPromotionRuntimeTest','-e','hostedAllowPublicTransport','true',app+'.test/androidx.test.runner.AndroidJUnitRunner'],value=>{log+=value;if(log.length>4*1024*1024)main.kill();},()=>{mainExited=true;});main=started.child;
  const until=Date.now()+230000,readyDeadline=Math.min(until,Date.now()+90000);let ready;
  while(Date.now()<readyDeadline&&!mainExited){const raw=await read('stream-promotion-ready.json');if(raw){ready=JSON.parse(raw);break;}await sleep(250);}
  if(!ready||!ready.tun||!Number.isInteger(ready.uid)||ready.uid<=0||!/^AUTOPILOT_STREAM_[a-f0-9-]{36}$/.test(ready.marker||'')||!/^[a-f0-9-]{36}$/.test(ready.nonce||''))throw Error('Stream fixture readiness invalid: '+log.slice(-8000));
  await fs.writeFile(path.join(output,'stream-promotion-ready.json'),JSON.stringify(ready,null,2));
  const observed=launch(['-e','marker',ready.marker,'app.autopilot.probe/.StreamPromotionProbe'],value=>{probeLog+=value;if(probeLog.length>1024*1024)probe.kill();},()=>{probeExited=true;});probe=observed.child;
  const probeStatus=await bounded(observed.done,until,'Separate-UID stream probe');
  const uid=Number(/(?:^|\n)INSTRUMENTATION_RESULT: uid=(\d+)\s*(?:\r?\n|$)/.exec(probeLog)?.[1]);
  if(probeStatus!==0||!Number.isInteger(uid)||uid<=0||uid===ready.uid||!/^INSTRUMENTATION_RESULT: result=PASS\s*$/m.test(probeLog)||!/^INSTRUMENTATION_CODE: -1\s*$/m.test(probeLog)
    ||!/^INSTRUMENTATION_RESULT: network=handle=\d+,vpn=true,blocked=false\s*$/m.test(probeLog))throw Error('Separate-UID stream failed: '+probeLog);
  for(const name of['sameSocket','newConnectionUsesPromotedOutbound','confirmedFailureClosedStream'])if(!new RegExp('^INSTRUMENTATION_RESULT: '+name+'=true\\s*$','m').test(probeLog))throw Error('Missing stream observable: '+name);
  const after=Number(/^INSTRUMENTATION_RESULT: messagesAfterPromotion=(\d+)\s*$/m.exec(probeLog)?.[1]);if(!Number.isInteger(after)||after<3)throw Error('Insufficient bidirectional stream continuity');
  const encoded=Buffer.from(JSON.stringify({nonce:ready.nonce,result:'PASS',uid}),'utf8').toString('base64');
  await adb('shell','run-as',app,'sh','-c',"'printf %s "+encoded+" | base64 -d > files/stream-promotion-complete.tmp && mv files/stream-promotion-complete.tmp files/stream-promotion-complete'");
  const status=await bounded(started.done,until,'Production stream instrumentation');await save();
  if(status!==0||!/^OK \(1 test\)\s*$/m.test(log)||/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|There (?:was|were) \d+ failures?/.test(log)||!log.includes('AUTOPILOT_STREAM_PROMOTION_RUNTIME_ACCEPTED'))throw Error('Production stream instrumentation failed: '+log.slice(-12000));
  const evidence=JSON.parse(await read('stream-promotion-evidence.json')),fixture=JSON.parse(await read('stream-promotion-fixture.json'));
  const required=['oldProofExpiredBeforeDiscovery','automaticDiscoveryPromotion','oldSocketContinuous','newSocketUsesSecondOutbound','confirmedWatchdogFailure','confirmedRecoveryClosedStream','nativeReject','separateUid','platformTls','threeWatchdogOrigins','stopCleanup','fixtureClosed'];
  for(const name of required)if(evidence[name]!==true)throw Error('Missing final production stream evidence: '+name);
  if(evidence.tunStarts!==1||evidence.openFixtureSockets!==0||evidence.oldSocketMessagesAfterPromotion<3||!Number.isInteger(evidence.promotionMs)||evidence.promotionMs>20000
    ||fixture.afterPromotionAcks<3||fixture.freshSecondConnections<1||fixture.streamClosed!==true||fixture.denied!==0||fixture.failure!==''||fixture.openSockets!==0||fixture.closed!==true
    ||fixture.tlsSent<=0||fixture.tlsReceived<=0||JSON.stringify([...fixture.tlsHosts].sort())!==JSON.stringify(['api.anthropic.com','api.openai.com','www.gstatic.com']))throw Error('Production stream fixture/counter evidence invalid');
  const receipt='ANDROID_STREAM_PROMOTION_RUNTIME_RECEIPT tests=1 expected=1 failures=0 errors=0 skipped=0 automaticDiscovery=true staleOldProof=true sameOldSocket=true newSocketNewOutbound=true confirmedRecoveryCloses=true separateUid=true tunStarts=1 platformTls=true stopCleanup=true fixtureCleanup=true\n';
  process.stdout.write(receipt);await fs.writeFile(path.join(output,'stream-promotion-receipt.txt'),receipt);
}
mainRun().catch(async error=>{console.error(error.stack||error.message);process.exitCode=1;try{await save();}catch{}}).finally(async()=>{if(serial){try{await adb('shell','am','force-stop',app);await adb('shell','am','force-stop','app.autopilot.probe');}catch{}}if(main&&!mainExited)main.kill();if(probe&&!probeExited)probe.kill();});
