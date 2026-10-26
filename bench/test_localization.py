"""Release resource parity and canonical inference text protection."""
import pathlib,re,unittest,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
class LocalizationTests(unittest.TestCase):
 def strings(self,folder):return {s.attrib['name']:s.text for s in ET.parse(ROOT/'app/src/main/res'/folder/'strings.xml').getroot()}
 def test_complete_russian_and_english_resources(self):
  en,ru=self.strings('values'),self.strings('values-ru')
  self.assertGreater(len(en),800);self.assertEqual(en.keys(),ru.keys())
  for key,value in en.items():
   self.assertEqual(sorted(re.findall(r'%\d+\$s',value)),sorted(re.findall(r'%\d+\$s',ru[key])),key)
   if key!='ui_russian':self.assertIsNone(re.search('[А-Яа-яЁё]',value),key)
 def test_only_complete_locales_are_advertised(self):
  locales=ET.parse(ROOT/'app/src/main/res/xml/locales_config.xml').getroot()
  self.assertEqual(['en','ru'],[n.attrib['{http://schemas.android.com/apk/res/android}name'] for n in locales])
 def test_all_string_references_resolve(self):
  keys=self.strings('values')
  for file in (ROOT/'app/src/main/java').rglob('*.kt'):
   for key in re.findall(r'(?<!android\.)R\.string\.(\w+)',file.read_text()):self.assertIn(key,keys,(file.name,key))
 def test_file_prompt_markers_are_not_ui_translations(self):
  source=(ROOT/'app/src/main/java/org/motoai/runtime/ChatFiles.kt').read_text()
  self.assertIn('append("\\n\\n[Файл: ")',source);self.assertIn('append("\\n[Конец файла]\\n")',source)
if __name__=='__main__':unittest.main()
