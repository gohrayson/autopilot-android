'use strict';
// Runs only on a local Android emulator. Never installs or changes VPNs on a physical device.
const fs=require('node:fs/promises');
const path=require('node:path');
const net=require('node:net');
const cp=require('node:child_process');
const {promisify}=require('node:util');
const {pathToFileURL}=require('node:url');
const execute=promisify(cp.execFile);
const root=path.resolve(__dirname,'../..');
const work=path.join(root,'.data/android-runtime');
const sdk=process.env.ANDROID_HOME||path.join(root,'.data/android-tools/sdk');
const adb=path.join(sdk,'platform-tools/adb.exe');
const app='app.autopilot.android.debug';
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let serial,fixture,instrument,initial,protectedState;
const runAdb=async(...args)=>(await execute(adb,['-s',serial,...args],{timeout:240000,maxBuffer:8*1024*1024,windowsHide:true})).stdout;
async function readPrivate(name){try{return await runAdb('shell','run-as',app,'cat','files/'+name);}catch{return null;}}
async function main(){
  ({protectedState}=await import(pathToFileURL(path.join(root,'scripts/probe-runtime.mjs')).href));
  initial=await protectedState();
  await fs.mkdir(work,{recursive:true});
  await fs.writeFile(path.join(work,'windows-before.json'),JSON.stringify(initial,null,2));
  const devices=(await execute(adb,['devices'],{windowsHide:true})).stdout.split(/\r?\n/).filter(l=>/^emulator-\d+\s+device$/.test(l)).map(l=>l.split(/\s/)[0]);
  serial=process.argv[2]||devices[0];
  if(!/^emulator-\d+$/.test(serial||'')||!devices.includes(serial))throw new Error('An online local emulator is required. Physical devices are refused.');
  await new Promise((resolve,reject)=>{const test=net.createServer();test.once('error',reject);test.listen(29480,'127.0.0.1',()=>test.close(resolve));});
  const metadata=path.join(work,'fixture.json');
  await fs.rm(metadata,{force:true});
  fixture=cp.spawn(process.execPath,[path.join(__dirname,'tunnel-fixture.cjs'),metadata],{cwd:root,stdio:['ignore','pipe','pipe'],windowsHide:true});
  fixture.stdout.on('data',c=>process.stdout.write(c));fixture.stderr.on('data',c=>process.stderr.write(c));
  for(let i=0;i<50;i++){try{JSON.parse(await fs.readFile(metadata,'utf8'));break;}catch{await sleep(100);}}
  for(const apk of ['android/app/build/outputs/apk/debug/app-debug.apk','android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk','android/probe/build/outputs/apk/debug/probe-debug.apk']){
    const result=await runAdb('install','-r','-t',path.join(root,apk));if(!result.includes('Success'))throw new Error('APK install failed: '+result.trim());
  }
  await runAdb('shell','pm','clear',app);
  await runAdb('shell','appops','set',app,'ACTIVATE_VPN','allow');
  await runAdb('shell','pm','grant',app,'android.permission.POST_NOTIFICATIONS');
  let testOutput='';
  instrument=cp.spawn(adb,['-s',serial,'shell','am','instrument','-w','-e','class','app.autopilot.android.AndroidSmokeTest',app+'.test/androidx.test.runner.AndroidJUnitRunner'],{windowsHide:true});
  instrument.stdout.on('data',b=>{testOutput+=b.toString();process.stdout.write(b);});instrument.stderr.on('data',b=>{testOutput+=b.toString();process.stderr.write(b);});
  let exited=false;const completion=new Promise(resolve=>instrument.once('exit',code=>{exited=true;resolve(code);}));
  let ready;
  const deadline=Date.now()+200000;
  while(Date.now()<deadline&&!exited){const raw=await readPrivate('probe-ready.json');if(raw){ready=JSON.parse(raw);break;}await sleep(750);}
  if(!ready)throw new Error('Android VPN did not become ready. Instrumentation: '+testOutput.slice(-5000));
  await fs.writeFile(path.join(work,'native-ready.json'),JSON.stringify(ready,null,2));
  await runAdb('shell','screencap','-p','/sdcard/autopilot-runtime.png');
  await runAdb('pull','/sdcard/autopilot-runtime.png',path.join(work,'android-connected.png'));
  const {marker}=JSON.parse(await fs.readFile(metadata,'utf8'));
  const outputs=[];
  for(const url of ['http://203.0.113.7/autopilot-probe','http://[2001:db8::7]/autopilot-probe']){
    const output=await runAdb('shell','am','instrument','-w','-e','url',url,'-e','marker',marker,'app.autopilot.probe/.TunnelProbe');
    outputs.push(output);process.stdout.write(output);
    if(!/result=PASS/.test(output))throw new Error('Separate-UID tunnel traffic failed: '+output);
    const uid=/uid=(\d+)/.exec(output);if(!uid||Number(uid[1])===ready.uid)throw new Error('Probe UID was not separate from the VPN owner');
  }
  const finalFixture=JSON.parse(await fs.readFile(metadata,'utf8'));
  if(finalFixture.markerHits<2)throw new Error('TUN packets did not reach the marker fixture');
  await runAdb('shell','run-as',app,'sh','-c',"'echo PASS > files/probe-complete'");
  const code=await completion;
  await fs.writeFile(path.join(work,'instrumentation.log'),testOutput);
  await fs.writeFile(path.join(work,'tunnel.log'),outputs.join('\n'));
  if(code!==0||!/OK \(1 test\)/.test(testOutput))throw new Error('Native Android smoke test failed: '+testOutput.slice(-6000));
  console.log('PASS: AndroidKeyStore, native core, selection, distinct-UID IPv4/IPv6 TUN traffic and Stop cleanup.');
}
main().catch(error=>{process.stderr.write(error.stack+'\n');process.exitCode=1;}).finally(async()=>{
  if(serial){try{await runAdb('shell','am','force-stop',app);}catch{} }
  if(instrument&&!instrument.killed)instrument.kill();
  if(fixture&&!fixture.killed)fixture.kill();
  if(protectedState&&initial){const after=await protectedState();await fs.writeFile(path.join(work,'windows-after.json'),JSON.stringify(after,null,2));if(JSON.stringify(after)!==JSON.stringify(initial)){process.stderr.write('Host VPN state differs from probe baseline.\n');process.exitCode=1;}else console.log('PASS: Windows VPN processes, proxy and port 7890 unchanged.');}
});
