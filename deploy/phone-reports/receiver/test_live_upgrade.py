import importlib.util,unittest
from pathlib import Path
import synthetic_writer
spec=importlib.util.spec_from_file_location('upgrade_live',Path(__file__).with_name('upgrade-live.py'))
upgrade=importlib.util.module_from_spec(spec);spec.loader.exec_module(upgrade)

class MigrationTests(unittest.TestCase):
 def source(self):return Path(synthetic_writer.__file__).read_text()
 def test_expanded_validators_preserved_and_migration_is_idempotent(self):
  old=self.source()+"\ndef validate_challenge_evidence(r):\n    return r['frozen_challenge']\n\ndef sanitize_dialog_clinic(r):\n    return r['frozen_clinic']\n"
  new=upgrade.writer_upgrade(old,self.source())
  for name in ['sanitize','validate_challenge_evidence','sanitize_dialog_clinic']:
   self.assertEqual(upgrade.function(old,name),upgrade.function(new,name))
  self.assertEqual(new,upgrade.writer_upgrade(new,self.source()))
  self.assertEqual(new.count('from live_publisher import prepare_live'),1)
 def test_unknown_layout_fails_closed(self):
  source=self.source().replace("'unexpected destination files/workflows'","'unknown layout'")
  with self.assertRaises(ValueError):upgrade.writer_upgrade(source,self.source())
 def test_missing_existing_phone_hook_fails_closed(self):
  source=self.source().replace("prepare(Path('/var/lib/aibrowser-phone-receiver/outbox'),repo)","prepare_other(repo)")
  with self.assertRaises(ValueError):upgrade.writer_upgrade(source,self.source())
