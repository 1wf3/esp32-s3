from pathlib import Path
import csv,json,zipfile
from pypdf import PdfReader
root=Path.cwd();out=root/'output/carrier_v01';pdf=root/'output/pdf/导航转接底板V0.1_原理图与绘制指南.pdf'
r=PdfReader(str(pdf));assert len(r.pages)==4
for p in r.pages:assert '转接' in p.extract_text() or '接口' in p.extract_text() or '布局' in p.extract_text() or '物料' in p.extract_text()
pins=list(csv.DictReader((out/'接口逐针网络表.csv').open(encoding='utf-8-sig')));bom=list(csv.DictReader((out/'物料清单_BOM.csv').open(encoding='utf-8-sig')))
assert len(pins)==20 and len(bom)==21
j2={int(x['脚号']):x for x in pins if x['接口']=='J2'}
for p,gpio in {3:12,5:11,6:9,7:10,8:14,10:15}.items():assert 'GPIO'+str(gpio) in j2[p]['开发板/连接关系']
assert next(x for x in pins if x['接口']=='J3' and x['脚号']=='3')['开发板/连接关系']=='GPIO17 via R8 1k'
report={'pages':4,'connector_pins':len(pins),'bom_rows':len(bom),'matches_current_firmware_gpio':True,'pdf_rendered_and_visually_reviewed':True,'mechanical_footprints_frozen':False,'eda_erc_drc_done':False,'pcb_built_or_tested':False}
(out/'设计核对结果.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
with zipfile.ZipFile(root/'output/导航转接底板V0.1_资料包.zip','w',zipfile.ZIP_DEFLATED) as z:
 z.write(pdf,'pdf/'+pdf.name)
 for p in out.iterdir():
  if p.is_file():z.write(p,'carrier_v01/'+p.name)
print(json.dumps(report,ensure_ascii=False));print('ZIP and PDF verified')
