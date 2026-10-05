import {readFileSync,writeFileSync,existsSync,lstatSync,mkdirSync,copyFileSync,rmSync,unlinkSync,openSync,closeSync} from 'node:fs';
import {join,dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
export function acquireStationLock(root){
 const path=join(root,'.station.lock');
 if(existsSync(path)){
  const pid=JSON.parse(readFileSync(path,'utf8')).pid;
  try{process.kill(pid,0);throw new Error('Trainer already running or updating.');}catch(e){if(e.code!=='ESRCH')throw e;unlinkSync(path);}
 }
 const fd=openSync(path,'wx');writeFileSync(fd,JSON.stringify({pid:process.pid}));closeSync(fd);
 return ()=>{if(existsSync(path))unlinkSync(path);};
}
export function installBundle(stage,root,refresh=()=>{}){
 const lock=join(root,'data','runner.lock');
 if(existsSync(lock)){
  const pid=JSON.parse(readFileSync(lock,'utf8')).pid;
  try{process.kill(pid,0);throw new Error('Stop training before updating.');}catch(e){if(e.code!=='ESRCH')throw e;}
 }
 const release=acquireStationLock(root);
 try{
  const manifest=JSON.parse(readFileSync(join(stage,'bundle-manifest.json'),'utf8'));
  if(!/^\d+\.\d+\.\d+$/.test(manifest.version)||!manifest.files||!Object.keys(manifest.files).length)throw new Error('Unexpected update manifest.');
  const names=Object.keys(manifest.files);
  for(const name of names){
   if(!/^(?:[A-Za-z0-9_-]+\.(?:mjs|json|ps1|cmd|html|txt)|site-brain-trainer\.jar|content\.js|tests\/[A-Za-z0-9_.-]+\.(?:mjs|ps1))$/.test(name)||name==='bundle-manifest.json')throw new Error('Unexpected update file: '+name);
   const path=join(stage,name);
   if(!existsSync(path)||!lstatSync(path).isFile()||createHash('sha256').update(readFileSync(path)).digest('hex')!==manifest.files[name])throw new Error('Update file is damaged: '+name);
  }
  names.push('bundle-manifest.json');
  const backup=join(root,'.update-backup');rmSync(backup,{recursive:true,force:true});mkdirSync(backup);
  const old=new Set();
  for(const name of names)if(existsSync(join(root,name))){mkdirSync(dirname(join(backup,name)),{recursive:true});copyFileSync(join(root,name),join(backup,name));old.add(name);}
  try{
   for(const name of names){mkdirSync(dirname(join(root,name)),{recursive:true});copyFileSync(join(stage,name),join(root,name));}
  }catch(e){
   for(const name of names){if(old.has(name))copyFileSync(join(backup,name),join(root,name));else rmSync(join(root,name),{force:true});}
   throw e;
  }
  refresh();
  return manifest.version;
 }finally{release();}
}
function refreshTools(root){
 const r=spawnSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',join(root,'Setup.ps1'),'-WithinStationLock','-NoPause'],{stdio:'inherit',windowsHide:true});
 if(r.error)throw r.error;
 if(r.status!==0)throw new Error('Browser setup needs retry. Press Start to finish setup. Saved training remains.');
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 try{
  if(process.argv[2]==='--setup'){
   const root=process.argv[3],release=acquireStationLock(root);
   try{refreshTools(root);}finally{release();}
  }else{
   const root=process.argv[3];
   console.log('Updated to '+installBundle(process.argv[2],root,process.argv.includes('--refresh')?()=>refreshTools(root):undefined)+'. Saved training remains.');
  }
 }catch(e){console.error(e.message);process.exitCode=1;}
}
