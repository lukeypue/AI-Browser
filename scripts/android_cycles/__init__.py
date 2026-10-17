"""Host Android cycle controller and shared independent wire validator."""
import importlib.util,sys
from pathlib import Path
# Source checkout and installed /opt/aibrowser-android-cycles layout both carry receiver.
_receiver=Path(__file__).resolve().parents[2]/'deploy/phone-reports/receiver'
if not _receiver.is_dir():_receiver=Path(__file__).resolve().parents[1]/'receiver'
sys.path.insert(0,str(_receiver))
_spec=importlib.util.spec_from_file_location('_android_cycle_wire',_receiver/'android_cycles.py')
_wire=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(_wire)
validate_android_cycle=_wire.validate_android_cycle
canonical_report=_wire.canonical_report
