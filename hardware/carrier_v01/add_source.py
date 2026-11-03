from pathlib import Path
p=Path('hardware/carrier_v01/generate.py');s=p.read_text('utf-8-sig');needle='- 中科微ATGM336H-5N：https://icofchina.com/daohang/danpin/2525.html';s=s.replace(needle,'- JST XH 官方规格：https://www.jst.com/wp-content/uploads/2025/06/eXH.pdf （针距2.50 mm；连接器和压接端子需同系列匹配）\n'+needle);p.write_text(s,encoding='utf8')
p=Path('output/carrier_v01/嘉立创绘制说明.md');s=p.read_text('utf8');s=s.replace(needle,'- JST XH 官方规格：https://www.jst.com/wp-content/uploads/2025/06/eXH.pdf （针距2.50 mm；连接器和压接端子需同系列匹配）\n'+needle);p.write_text(s,encoding='utf8')
