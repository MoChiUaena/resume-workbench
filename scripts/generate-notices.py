"""Inventory resolved runtime JARs and locked npm packages; retain upstream license texts.

Run dependency:list first with includeScope=runtime and absolute artifact filenames.
No credentials or machine-specific paths are written to the generated inventory.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[1]
NS={'m':'http://maven.apache.org/POM/4.0.0'}
DEST=ROOT/'src/main/resources/META-INF/third-party'

def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for part in iter(lambda:stream.read(1048576),b''): digest.update(part)
    return digest.hexdigest()

def pom_file(repo,group,artifact,version):
    return repo/Path(*group.split('.'))/artifact/version/f'{artifact}-{version}.pom'

def pom_metadata(path,repo,seen=None):
    seen=set() if seen is None else seen
    if path in seen or not path.exists(): return [],''
    seen.add(path);root=ET.parse(path).getroot()
    licenses=[{'name':item.findtext('m:name','',NS),'url':item.findtext('m:url','',NS)} for item in root.findall('m:licenses/m:license',NS)]
    site=root.findtext('m:url','',NS)
    parent=root.find('m:parent',NS)
    if parent is not None and (not licenses or not site):
        inherited,parent_site=pom_metadata(pom_file(repo,parent.findtext('m:groupId','',NS),parent.findtext('m:artifactId','',NS),parent.findtext('m:version','',NS)),repo,seen)
        licenses=licenses or inherited;site=site or parent_site
    return licenses,site

def write_text(key,name,text):
    safe=re.sub(r'[^A-Za-z0-9_.-]','-',name)
    if not safe.lower().endswith('.txt'): safe+='.txt'
    target=DEST/'texts'/key/safe
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(text,encoding='utf-8')
    return target.relative_to(DEST).as_posix()

def generate(args):
    java=[];npm=[];missing=[]
    pattern=re.compile(r'^\s+([^:\s]+):([^:\s]+):jar:([^:\s]+):(compile|runtime):(.+?\.jar)(?:\s+--.*)?$')
    for line in args.maven_list.read_text(encoding='utf-8').splitlines():
        match=pattern.match(line)
        if not match: continue
        group,artifact,version,scope,filename=match.groups();jar=Path(filename)
        licenses,site=pom_metadata(pom_file(args.maven_repo,group,artifact,version),args.maven_repo)
        coordinate=f'{group}:{artifact}:{version}';texts=[]
        with zipfile.ZipFile(jar) as archive:
            for member in archive.namelist():
                if re.search(r'(^|/)(license[^/]*|notice[^/]*|thirdpartynotices\.txt)$',member,re.I) and not member.endswith('/') and Path(member).suffix.lower() not in {'.class','.jar','.exe','.dll','.so','.node','.dylib'}:
                    text=archive.read(member).decode('utf-8',errors='replace')
                    texts.append(write_text('java-'+artifact+'-'+version,member.replace('/','-'),text))
        source_jar=jar.with_name(jar.stem+'-sources.jar')
        if not texts and source_jar.exists():
            with zipfile.ZipFile(source_jar) as archive:
                for member in archive.namelist():
                    if not member.endswith('.java'): continue
                    text=archive.read(member).decode('utf-8',errors='replace')
                    first=text[:12000]
                    if 'copyright' in first.lower():
                        end=first.find('*/')
                        header=first[:end+2] if end>=0 else first.split('package ',1)[0]
                        texts.append(write_text('java-'+artifact+'-'+version,'SOURCE-COPYRIGHT',header+'\n'))
                        break
        # Supplemental upstream terms may have been obtained directly where the
        # Maven binary/source archive does not contain a top-level LICENSE.
        supplement=DEST/'texts'/('java-'+artifact+'-'+version)/'LICENSE-SDK.txt'
        if supplement.exists(): texts.append(supplement.relative_to(DEST).as_posix())
        if not licenses: missing.append(coordinate)
        source=f'https://repo.maven.apache.org/maven2/{group.replace(".","/")}/{artifact}/{version}/{artifact}-{version}-sources.jar'
        java.append({'coordinate':coordinate,'scope':scope,'packaged':not artifact.startswith('spring-boot-starter'),'sha256':sha(jar),'licenses':licenses,'projectUrl':site,'sourceArchive':source,'upstreamTexts':texts})
    assert java,'No runtime JARs found; check dependency:list arguments'
    lock=json.loads((ROOT/'frontend/package-lock.json').read_text(encoding='utf-8'))
    for location,package in sorted(lock['packages'].items()):
        if not location: continue
        name=location.rsplit('node_modules/',1)[-1];version=package['version'];license_id=package.get('license','');texts=[]
        installed=ROOT/'frontend'/location
        if installed.is_dir():
            for path in sorted(installed.iterdir()):
                if path.is_file() and re.match(r'^(license|licence|copying|notice)',path.name,re.I):
                    texts.append(write_text('npm-'+name.replace('/','-')+'-'+version,path.name,path.read_text(encoding='utf-8',errors='replace')))
        if not license_id: missing.append(name+'@'+version)
        npm.append({'name':name,'version':version,'license':license_id,'role':'build/test' if package.get('dev') else 'production dependency tree','optional':package.get('optional',False),'integrity':package.get('integrity',''),'upstreamTexts':texts})
    if missing: raise SystemExit('Unresolved license declarations: '+', '.join(missing))
    java.sort(key=lambda x:x['coordinate'])
    result={'inventoryVersion':1,'applicationVersion':'0.1.0','scope':'Resolved Maven runtime JARs and entire npm lockfile; OS/JDK/browser retain their own notices in the image.','java':java,'npm':npm,'unresolvedDeclarations':missing}
    DEST.mkdir(parents=True,exist_ok=True)
    (DEST/'inventory.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    lines=['# 依赖许可证清单','',f'解析结果：{len(java)} 个 Maven 运行期依赖（其中 {sum(x["packaged"] for x in java)} 个实际 JAR 随应用分发）、{len(npm)} 个 npm 锁定包，未识别声明为 0。','',
      '依据已解析 POM（含父 POM）和 npm lockfile 的上游声明。原始 JAR 的 LICENSE / NOTICE 保留在各依赖内，并提取随应用分发；npm 已安装包的许可证文本同样附带。完整清单、SHA-256 / integrity 和文本位于应用 JAR 的 `META-INF/third-party/`。','',
      '前端只分发构建后的 JS/CSS；npm 清单为保守的生产树及构建工具清单，不表示所有包都在浏览器执行。平台可选包即使未安装，也记录其锁定许可声明。','',
      '## Java 运行期依赖','', '| 坐标 | 上游许可声明 |','| --- | --- |']
    for item in java: lines.append('| '+item['coordinate']+' | '+' / '.join(x['name'] for x in item['licenses'])+' |')
    lines+=['','## npm 锁定依赖','','| 名称 | 版本 | 许可 | 用途 |','| --- | --- | --- | --- |']
    for item in npm: lines.append(f'| {item["name"]} | {item["version"]} | {item["license"]} | {item["role"]} |')
    lines+=['','## 分发与来源','',
      '上游库保持各自许可证，项目 MIT 不覆盖第三方代码。Logback 的上游双许可声明完整保留；Jakarta API 的 EPL / GPL + Classpath Exception 选择声明完整保留。Java 源码归档的官方 Maven 地址记录在 inventory.json；未修改这些依赖 JAR。','',
      'JDK 的条款保留在镜像 `/opt/java/openjdk/legal/`；Chromium/Playwright 的条款保留在 `/ms-playwright/` 与 `/ms-playwright-driver/`；基础系统包的 copyright 文件保留在 `/usr/share/doc/`。PostgreSQL 是单独的官方服务镜像，按 PostgreSQL License 分发。字体 OFL 和用户提供的奶龙图片边界见 THIRD_PARTY_NOTICES.md。','',
      '重新生成：`mvnw dependency:list -DincludeScope=runtime -DoutputAbsoluteArtifactFilename=true -DoutputFile=target/runtime-dependencies.txt`，执行 `npm ci` 后运行 `python scripts/generate-notices.py`。不要把包含宿主机路径的原始 dependency:list 输出提交到仓库。','']
    (ROOT/'docs/dependency-licenses.md').write_text('\n'.join(lines),encoding='utf-8')
    print(f'Inventoried {len(java)} runtime JARs and {len(npm)} npm packages; 0 missing declarations.')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--maven-list',type=Path,default=ROOT/'target/runtime-dependencies.txt')
    parser.add_argument('--maven-repo',type=Path,default=Path.home()/'.m2/repository')
    generate(parser.parse_args())
