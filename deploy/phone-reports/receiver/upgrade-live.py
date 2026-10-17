"""Explicit verified live migration; no credentials read or signing/release operations."""
import ast,fcntl,hashlib,os,shutil,subprocess,tempfile
from pathlib import Path

SOURCE=Path(__file__).resolve().parent
RECEIVER=Path('/opt/aibrowser-phone-receiver')
SHARING=Path('/opt/ai-browser-report-sharing')
UNITS=Path('/etc/systemd/system')

def function(source,name):
 tree=ast.parse(source)
 node=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name==name)
 return ''.join(source.splitlines(keepends=True)[node.lineno-1:node.end_lineno])

def writer_upgrade(old,new):
 # Preserve every expanded city/clinic validator already installed.
 saved={n.name:function(old,n.name) for n in ast.parse(old).body if isinstance(n,ast.FunctionDef) and n.name in {'sanitize','validate_challenge_evidence','sanitize_dialog_clinic'}}
 if 'def destination_allowed(' not in old:old=old.replace('def prepare_all(repo):',function(new,'destination_allowed')+'\n\ndef prepare_all(repo):',1)
 lines=old.splitlines(keepends=True)
 matches=[i for i,line in enumerate(lines) if "'unexpected destination files/workflows'" in line]
 if len(matches)!=1:raise ValueError('unknown writer layout')
 lines[matches[0]]="    require(all(destination_allowed(p) for p in tracked),'unexpected destination files/workflows')\n"
 old=''.join(lines)
 marker="    prepare(Path('/var/lib/aibrowser-phone-receiver/outbox'),repo)"
 if old.count(marker)!=1:raise ValueError('unknown publisher hook')
 if 'from live_publisher import prepare_live' not in old:old=old.replace(marker,marker+"\n    from live_publisher import prepare_live\n    prepare_live(Path('/var/lib/aibrowser-phone-receiver/live'),Path('/var/lib/aibrowser-phone-live-triage'),repo)")
 old=old.replace(function(old,'push_expected'),function(new,'push_expected'),1)
 old=old.replace('except subprocess.CalledProcessError:', 'except (subprocess.CalledProcessError,ValueError):')
 ast.parse(old)
 for name,value in saved.items():
  if function(old,name)!=value:raise ValueError('city validator changed')
 return old

def install(source,destination):
 destination.parent.mkdir(parents=True,exist_ok=True)
 with tempfile.NamedTemporaryFile(dir=destination.parent,delete=False) as tmp:
  tmp.write(source);tmp.flush();os.fsync(tmp.fileno());name=Path(tmp.name)
 name.chmod(0o644);name.replace(destination)

def command(*args):return subprocess.run(args,check=True,text=True)

def main():
 if os.geteuid()!=0:raise SystemExit('root deployment required')
 # Code-only inputs; never read credential/config/signing files.
 command('curl','--fail','--silent','--show-error','--connect-timeout','15','https://reports.ryzod.com/health')
 command('sha256sum','-c',str(SHARING/'training-units.sha256'))
 lock=(Path('/var/lib/aibrowser-practice-city-upload')/'upload.lock').open('a')
 fcntl.flock(lock,fcntl.LOCK_EX)
 old=(SHARING/'export_reports.py').read_text();updated=writer_upgrade(old,(SOURCE/'synthetic_writer.py').read_text())
 receiver_files=['receiver.py','live_progress.py','live_triage.py','triage_codex.py']
 sharing_files=['receiver.py','live_progress.py','live_triage.py','live_publisher.py']
 for name in set(receiver_files+sharing_files):compile((SOURCE/name).read_text(),name,'exec')
 # Unit permissions let only the existing upload group read typed latest output.
 unit=(SOURCE/'aibrowser-phone-live-triage.service').read_text()
 if 'Group=aibrowserupload' not in unit or 'StateDirectoryMode=0750' not in unit:raise ValueError('unsafe output permissions')
 backup=SHARING/'before-live-progress';backup.mkdir(mode=0o700,exist_ok=True)
 for directory,names in [(RECEIVER,receiver_files),(SHARING,sharing_files+['export_reports.py'])]:
  for name in names:
   file=directory/name;target=backup/(directory.name+'-'+name)
   if file.exists() and not target.exists():shutil.copyfile(file,target);target.chmod(0o600)
 for name in receiver_files:install((SOURCE/name).read_bytes(),RECEIVER/name)
 for name in sharing_files:install((SOURCE/name).read_bytes(),SHARING/name)
 install(updated.encode(),SHARING/'export_reports.py')
 for name in ['aibrowser-phone-live-triage.service','aibrowser-phone-live-triage.path']:install((SOURCE/name).read_bytes(),UNITS/name)
 command('install','-d','-o','root','-g','aibrowserupload','-m','750','/var/lib/aibrowser-phone-live-triage')
 dropin=UNITS/'aibrowser-practice-city-upload.path.d/live-progress.conf'
 install(b'[Path]\nPathChanged=/var/lib/aibrowser-phone-receiver/live\nPathChanged=/var/lib/aibrowser-phone-live-triage\n',dropin)
 fcntl.flock(lock,fcntl.LOCK_UN);lock.close()
 command('systemctl','daemon-reload')
 command('systemctl','restart','aibrowser-phone-receiver.service')
 # Keep advisory trigger disabled until the existing review integration is identified.
 command('systemctl','restart','aibrowser-practice-city-upload.path')
 # No second review automation is created or enabled during this migration.
 command('systemctl','start','aibrowser-practice-city-upload.service')
 command('curl','--fail','--silent','--show-error','--connect-timeout','15','https://reports.ryzod.com/health')
 command('sha256sum','-c',str(SHARING/'training-units.sha256'))
 command('systemctl','is-active','aibrowser-practice-city.timer','aibrowser-phone-receiver.service','aibrowser-practice-city-upload.path')
 print('Live ingress/publisher migrated; advisory trigger installed but disabled. Actual phone live delivery is not yet verified.')
if __name__=='__main__':main()
