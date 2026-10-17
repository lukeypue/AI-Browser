#!/usr/bin/env python3
"""One full cycle or persistent cycle loop; no accelerated completion mode."""
import argparse,subprocess,time
from pathlib import Path
from android_cycles.controller import Controller,Busy,read_json
def main():
 parser=argparse.ArgumentParser()
 parser.add_argument('--config',type=Path,default=Path('/opt/aibrowser-android-cycles/config.json'))
 parser.add_argument('--once',action='store_true')
 args=parser.parse_args();controller=Controller(read_json(args.config))
 while True:
  try:
   report=controller.run_once();print(report['cycle_id']+' '+report['status'],flush=True)
  except Busy:
   if args.once:raise
   time.sleep(30)
  except (OSError,ValueError,subprocess.SubprocessError):
   if args.once:raise
   print('cycle_controller_waiting',flush=True);time.sleep(60)
  if args.once:return
if __name__=='__main__':main()
