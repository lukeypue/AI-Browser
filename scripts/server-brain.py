#!/usr/bin/env python3
"""Run one private synthetic training cycle; external scheduler owns repetition."""
import argparse
import json
import time
from pathlib import Path
from server_brain.controller import State,run_once
from server_brain.transport import BrowserLab,CodexModel,JavaLab

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state',required=True,type=Path)
    parser.add_argument('--source-root',required=True,type=Path)
    parser.add_argument('--classpath',required=True)
    parser.add_argument('--source-sha256',required=True)
    parser.add_argument('--manifest-sha256',required=True)
    parser.add_argument('--java',default='/usr/bin/java')
    parser.add_argument('--node',default='/usr/bin/node')
    parser.add_argument('--playwright-module')
    parser.add_argument('--playwright-browsers-path')
    parser.add_argument('--rollback',action='store_true')
    args=parser.parse_args()
    try:
        state=State(args.state,args.source_sha256,args.manifest_sha256)
        if args.rollback:
            state.rollback();result={'status':'rolled_back'}
        else:
            result=run_once(state,JavaLab(args.source_root,args.classpath,args.java),CodexModel(),BrowserLab(args.source_root,args.node,args.playwright_module,playwright_browsers_path=args.playwright_browsers_path),time.time())
        print(json.dumps(result,sort_keys=True))
        return 1 if result['status']=='failed' else 0
    except Exception:
        print('{"status":"invalid_state_or_configuration"}')
        return 1

if __name__=='__main__':raise SystemExit(main())

