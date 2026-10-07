"""Generate small, deterministic Word packages containing synthetic resume text."""
import argparse
import base64
import io
from pathlib import Path
from xml.sax.saxutils import escape
import zipfile

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
PACKAGE_R = 'http://schemas.openxmlformats.org/package/2006/relationships'


def paragraph(text, heading=False):
    style = '<w:pPr><w:pStyle w:val="Heading1"/></w:pPr>' if heading else ''
    return f'<w:p>{style}<w:r><w:t xml:space="preserve">{escape(text)}</w:t></w:r></w:p>'


def fixture_bytes(table=False, long=False):
    intro = [('姓名：奶龙', False), ('求职意向：Java 后端实习', False),
             ('邮箱：nailong@example.invalid', False), ('电话：13800000000', False),
             ('城市：杭州', False)]
    blocks = ''.join(paragraph(text, heading) for text, heading in intro)
    blocks += paragraph('教育背景', True) + paragraph('示例理工大学 · 软件工程 · 2023–2027')
    blocks += paragraph('项目经历', True) + paragraph('本地简历工作台')
    blocks += paragraph('使用 Java 和 Spring Boot 实现接口校验，记录可复现的联调问题。')
    blocks += paragraph('专业技能', True) + paragraph('Java、PostgreSQL、Git、Docker')
    blocks += paragraph('补充信息', True) + paragraph('未归类文字也应保留：这是合成示例，不是真实个人简历。')
    if table:
        cells = ''.join(f'<w:tc><w:tcPr><w:tcW w:w="4500" w:type="dxa"/></w:tcPr>{paragraph(text)}</w:tc>'
                        for text in ['Word 表格', '表格单元格内容完整保留'])
        blocks += '<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w="4500"/><w:gridCol w:w="4500"/></w:tblGrid><w:tr>' + cells + '</w:tr></w:tbl>'
        blocks += '<w:p><w:r><w:pict><v:shape id="fixture-image" style="width:1pt;height:1pt"><v:imagedata r:id="rImage"/></v:shape></w:pict></w:r></w:p>'
    if long:
        blocks += paragraph('项目细节', True)
        for number in range(24):
            blocks += paragraph(f'导入段落 {number + 1:02d}：' + '为输入校验、错误提示和数据保存编写验证，核对中文文字与分页效果。' * 3)
    section = '<w:sectPr>' + ('<w:headerReference w:type="default" r:id="rHeader"/>' if table else '') + '<w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="900" w:right="900" w:bottom="900" w:left="900"/></w:sectPr>'
    document = f'<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="{W}" xmlns:r="{R}" xmlns:v="urn:schemas-microsoft-com:vml"><w:body>{blocks}{section}</w:body></w:document>'
    types = '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
    if table:
        types += '<Default Extension="png" ContentType="image/png"/><Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>'
    types += '</Types>'
    entries = {'[Content_Types].xml': types.encode(),
               '_rels/.rels': f'<Relationships xmlns="{PACKAGE_R}"><Relationship Id="rDoc" Type="{R}/officeDocument" Target="word/document.xml"/></Relationships>'.encode(),
               'word/document.xml': document.encode()}
    if table:
        entries['word/_rels/document.xml.rels'] = f'<Relationships xmlns="{PACKAGE_R}"><Relationship Id="rHeader" Type="{R}/header" Target="header1.xml"/><Relationship Id="rImage" Type="{R}/image" Target="media/image1.png"/></Relationships>'.encode()
        entries['word/header1.xml'] = f'<w:hdr xmlns:w="{W}">{paragraph("页眉补充：奶龙作品集")}</w:hdr>'.encode()
        entries['word/media/image1.png'] = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aAhsAAAAASUVORK5CYII=')
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        for name, data in entries.items():
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    return output.getvalue()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=Path(__file__).resolve().parents[1] / 'fixtures/docx')
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name, options in [('basic.docx', {}), ('table.docx', {'table': True}), ('long.docx', {'long': True})]:
        (args.output_dir / name).write_bytes(fixture_bytes(**options))
    print('Generated three synthetic DOCX fixtures.')
