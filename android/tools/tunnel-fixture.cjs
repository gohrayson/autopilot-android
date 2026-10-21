'use strict';
// Development-only SOCKS server. Marker destinations cannot succeed outside Autopilot's TUN.
// Ordinary checks use the already running host proxy; this never changes that proxy.
const net = require('node:net');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const marker='AUTOPILOT_TUNNEL_OK_'+crypto.randomBytes(12).toString('hex');
const output=process.argv[2];
if(!output)throw new Error('Provide an isolated output metadata path');
let markerHits=0;
const server=net.createServer(client=>{
  let buffer=Buffer.alloc(0),stage='hello',upstream=null;
  client.on('error',()=>upstream?.destroy());client.on('close',()=>upstream?.destroy());
  const reply=()=>client.write(Buffer.from([5,0,0,1,127,0,0,1,0,0]));
  const parse=data=>{
    buffer=Buffer.concat([buffer,data]);
    if(stage==='hello'){
      if(buffer.length<2||buffer.length<2+buffer[1])return;
      if(buffer[0]!==5||!buffer.subarray(2,2+buffer[1]).includes(0))return client.destroy();
      buffer=buffer.subarray(2+buffer[1]);client.write(Buffer.from([5,0]));stage='request';
    }
    if(stage!=='request'||buffer.length<4)return;
    let end,host;
    if(buffer[3]===1){end=10;if(buffer.length<end)return;host=[...buffer.subarray(4,8)].join('.');}
    else if(buffer[3]===3){end=7+buffer[4];if(buffer.length<end)return;host=buffer.subarray(5,5+buffer[4]).toString();}
    else if(buffer[3]===4){end=22;if(buffer.length<end)return;host=buffer.subarray(4,20).toString('hex');}
    else return client.destroy();
    if(buffer[0]!==5||buffer[1]!==1)return client.destroy();
    const request=buffer.subarray(0,end),rest=buffer.subarray(end);stage='stream';client.off('data',parse);
    if(host==='203.0.113.7'||host==='20010db8000000000000000000000007'){
      reply();const respond=()=>{markerHits++;fs.writeFileSync(output,JSON.stringify({marker,markerHits,port:29480}));const body=Buffer.from(marker+'\n');client.end('HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: '+body.length+'\r\nConnection: close\r\n\r\n'+body);};
      if(rest.length)respond();else client.once('data',respond);return;
    }
    upstream=net.connect(7890,'127.0.0.1',()=>upstream.write(Buffer.from([5,1,0])));
    upstream.on('error',()=>client.destroy());let response=Buffer.alloc(0),upstage=0;
    const negotiate=chunk=>{
      response=Buffer.concat([response,chunk]);
      if(upstage===0){if(response.length<2)return;if(response[0]!==5||response[1]!==0)return client.destroy();response=response.subarray(2);upstage=1;upstream.write(request);}
      if(upstage===1){if(response.length<4)return;const length=response[3]===1?10:response[3]===4?22:response[3]===3&&response.length>=5?7+response[4]:Infinity;if(response.length<length)return;if(response[1]!==0)return client.destroy();const extra=response.subarray(length);upstream.off('data',negotiate);reply();if(extra.length)client.write(extra);if(rest.length)upstream.write(rest);client.pipe(upstream);upstream.pipe(client);}
    };upstream.on('data',negotiate);
  };client.on('data',parse);client.setTimeout(45000,()=>client.destroy());
});
server.listen(29480,'127.0.0.1',()=>{fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify({marker,markerHits,port:29480}));process.stdout.write('Tunnel fixture ready on loopback 29480\n');});
process.on('SIGINT',()=>{server.close();process.exit(0);});
