"""Follow only published stable source over anonymous read-only connections. No releases."""
import json,os,re,ssl,subprocess,urllib.parse,urllib.request
from pathlib import Path
SOURCE_URL='https://github.com/lukeypue/AI-Browser.git'
BRANCH='refs/heads/ai-browser-v6-deep-search'
METADATA_URL='https://github.com/lukeypue/AI-Browser/releases/download/ai-browser-latest/latest-version.json'
LIMIT=16384
class SourceSyncError(ValueError):pass
def require(value,code):
    if not value:raise SourceSyncError(code)
def parse_metadata(raw):
    require(type(raw) is bytes and len(raw)<=LIMIT,'metadata_size')
    def pairs(items):
        result={}
        for key,value in items:
            require(key not in result,'metadata_duplicate_field');result[key]=value
        return result
    def invalid(_):raise SourceSyncError('metadata_number')
    try:value=json.loads(raw,object_pairs_hook=pairs,parse_constant=invalid)
    except (ValueError,UnicodeError) as error:raise SourceSyncError('invalid_metadata') from None
    require(type(value) is dict and set(value)=={'commit','versionCode','versionName'},'metadata_fields')
    require(type(value['commit']) is str and re.fullmatch('[0-9a-f]{40}',value['commit']),'metadata_revision')
    require(type(value['versionCode']) is int and 0<value['versionCode']<=2147483647,'metadata_version')
    require(type(value['versionName']) is str and re.fullmatch('[A-Za-z0-9][A-Za-z0-9.+_-]{0,79}',value['versionName']),'metadata_name')
    return value
def download_metadata():
    class Redirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,req,fp,code,msg,headers,newurl):
            parsed=urllib.parse.urlsplit(newurl)
            require(parsed.scheme=='https' and parsed.hostname in {'github.com','release-assets.githubusercontent.com','objects.githubusercontent.com'},'metadata_redirect')
            return super().redirect_request(req,fp,code,msg,headers,newurl)
    opener=urllib.request.build_opener(Redirect,urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    try:
        with opener.open(METADATA_URL,timeout=30) as response:
            require(response.status==200,'metadata_http');return response.read(LIMIT+1)
    except SourceSyncError:raise
    except (OSError,ValueError):raise SourceSyncError('metadata_unavailable') from None
class GitRepository:
    def __init__(self,project,remote=SOURCE_URL):self.project=Path(project);self.remote=remote
    def command(self,*args,allowed=(0,)):
        # Anonymous HTTPS is verified at setup. Never load global/system credentials
        # or invoke a credential helper; subprocess errors cannot print configuration.
        env={'HOME':'/nonexistent','PATH':'/usr/bin:/bin','LANG':'C.UTF-8','GIT_CONFIG_GLOBAL':'/dev/null','GIT_CONFIG_SYSTEM':'/dev/null','GIT_TERMINAL_PROMPT':'0','GIT_ASKPASS':'/bin/false'}
        try:r=subprocess.run(['/usr/bin/git','-c','credential.helper=','-c','core.askPass=/bin/false','-c','http.extraHeader=','-c','http.sslVerify=true','-c','core.hooksPath=/dev/null',*args],cwd=self.project,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=120)
        except (OSError,subprocess.SubprocessError):raise SourceSyncError('source_git_unavailable') from None
        require(r.returncode in allowed and len(r.stdout)<=65536,'source_git_rejected');return r
    def head(self,ref='HEAD'):
        value=self.command('rev-parse','--verify',ref+'^{commit}').stdout.decode('ascii').strip()
        require(re.fullmatch('[0-9a-f]{40}',value),'source_revision');return value
    def dirty(self):return bool(self.command('status','--porcelain','--untracked-files=normal').stdout.strip())
    def fetch(self):self.command('fetch','--no-tags',self.remote,BRANCH)
    def ancestor(self,old,new):return self.command('merge-base','--is-ancestor',old,new,allowed=(0,1)).returncode==0
    def forward(self,revision):self.command('merge','--ff-only',revision)
def sync_source(project,load_metadata=download_metadata,git=None):
    """Caller holds the existing project AND stable output run locks throughout."""
    git=git or GitRepository(project)
    require(not git.dirty(),'source_has_local_changes');before=git.head()
    meta=parse_metadata(load_metadata());target=meta['commit']
    if before==target:return {'source_revision':target,'updated':False}
    git.fetch();fetched=git.head('FETCH_HEAD')
    require(git.ancestor(target,fetched),'published_source_not_on_stable')
    require(git.ancestor(before,target),'source_not_fast_forward')
    require(not git.dirty() and git.head()==before,'source_changed_during_sync')
    git.forward(target)
    require(not git.dirty() and git.head()==target,'source_sync_incomplete')
    return {'source_revision':target,'updated':True}
