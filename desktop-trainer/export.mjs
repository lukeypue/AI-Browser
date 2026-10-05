import {readFileSync,writeFileSync,readdirSync,mkdirSync,existsSync} from 'node:fs';
import {join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
export function snapshotTraining(root,destination){
 const data=join(root,'data'),omitted=[];
 if(!existsSync(data))throw new Error('There are no results yet. Start the trainer first.');
 mkdirSync(destination,{recursive:true});
 function copy(directory,relative=''){
  for(const item of readdirSync(directory,{withFileTypes:true})){
   if(item.isSymbolicLink()||item.name.endsWith('.tmp'))continue;
   if(!relative&&(['connection.json','runner.lock','stop-requested'].includes(item.name)||item.name.endsWith('.log')))continue;
   const name=relative?relative+'/'+item.name:item.name,source=join(directory,item.name),target=join(destination,name);
   if(item.isDirectory()){mkdirSync(target,{recursive:true});copy(source,name);}
   else if(item.isFile()){
    try{writeFileSync(target,readFileSync(source));}
    catch(e){if(item.name.endsWith('.log')&&['EACCES','EPERM','EBUSY'].includes(e.code)){omitted.push(name);}else throw e;}
   }
  }
 }
 copy(data);
 const manifest=join(root,'bundle-manifest.json');
 if(existsSync(manifest))writeFileSync(join(destination,'bundle-manifest.json'),readFileSync(manifest));
 writeFileSync(join(destination,'export-notes.json'),JSON.stringify({capturedAt:new Date().toISOString(),omittedLiveLogs:omitted},null,2));
 return {omittedLiveLogs:omitted};
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 try{snapshotTraining(process.argv[2],process.argv[3]);}catch(e){console.error(e.message);process.exitCode=1;}
}
