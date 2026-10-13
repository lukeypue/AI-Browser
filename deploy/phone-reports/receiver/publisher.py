"""Independent validation before writing immutable phone reports into the Git writer."""
import hashlib,json,re,uuid
from pathlib import Path
from datetime import datetime
from receiver import validate,canonical,strict_load,check,exact,LIMIT

def document(raw):
 d=strict_load(raw);exact(d,{'report','receipt'});r=validate(d['report']);receipt=d['receipt']
 exact(receipt,{'report_id','sha256','received_at_utc'})
 check(receipt['report_id']==r['report_id']);check(receipt['sha256']==hashlib.sha256(canonical(r)).hexdigest())
 check(type(receipt['received_at_utc']) is str and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?\+00:00',receipt['received_at_utc']))
 datetime.fromisoformat(receipt['received_at_utc']);return d

def prepare(outbox,repository):
 outbox=Path(outbox);target=Path(repository)/'phone-reports';index=target/'index.json'
 entries={};new=[]
 if index.exists():
  data=strict_load(index.read_bytes());exact(data,{'schema_version','reports'});check(data['schema_version']==1 and type(data['reports']) is list)
  for entry in data['reports']:
   exact(entry,{'report_id','sha256','received_at_utc','status'})
   id=entry['report_id'];check(type(id) is str and str(uuid.UUID(id))==id and id not in entries)
   check(entry['status'] in {'unreviewed','reviewed'})
   existing=target/(id+'.json');check(existing.is_file() and not existing.is_symlink())
   doc=document(existing.read_bytes());check(doc['receipt']['sha256']==entry['sha256']);check(doc['receipt']['received_at_utc']==entry['received_at_utc'])
   entries[id]=entry
 if outbox.exists():
  for file in sorted(outbox.glob('*.json')):
   check(file.is_file() and not file.is_symlink() and file.stat().st_size<=LIMIT+1024)
   doc=document(file.read_bytes());id=doc['report']['report_id'];check(file.name==id+'.json')
   destination=target/(id+'.json');payload=json.dumps(doc,sort_keys=True,separators=(',',':'))+'\n'
   if destination.exists():check(document(destination.read_bytes())==doc,409)
   else:new.append((destination,payload))
   receipt=doc['receipt']
   if id in entries:check(entries[id]['sha256']==receipt['sha256'],409)
   else:entries[id]={**receipt,'status':'unreviewed'}
 if not entries:return
 target.mkdir(exist_ok=True)
 for file,payload in new:file.write_text(payload)
 payload=json.dumps({'schema_version':1,'reports':sorted(entries.values(),key=lambda e:(e['received_at_utc'],e['report_id']))},sort_keys=True,separators=(',',':'))+'\n'
 if not index.exists() or index.read_text()!=payload:index.write_text(payload)
