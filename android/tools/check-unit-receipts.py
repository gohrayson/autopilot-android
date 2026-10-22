#!/usr/bin/env python3
"""Assert every declared JVM test ran once; consume Gradle's JUnit XML receipts."""
from collections import Counter
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

project = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[1])
expected = set()
for source in (project / 'app/src/test/java').rglob('*Test.java'):
    text = re.sub(r'/\*.*?\*/|//[^\n]*', '', source.read_text(encoding='utf-8'), flags=re.S)
    package = re.search(r'\bpackage\s+([\w.]+)\s*;', text).group(1)
    methods = re.findall(r'@Test(?:\s*\([^)]*\))?\s+public\s+void\s+(\w+)\s*\(', text)
    if not methods:
        raise SystemExit(f'Cannot inventory test class {source}')
    expected.update((package + '.' + source.stem, method) for method in methods)
observed = Counter()
failed = errors = skipped = 0
for report in (project / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    for case in ET.parse(report).iter('testcase'):
        observed[(case.get('classname'), case.get('name'))] += 1
        failed += case.find('failure') is not None
        errors += case.find('error') is not None
        skipped += case.find('skipped') is not None
missing = expected - observed.keys()
unexpected = observed.keys() - expected
duplicates = {key for key, count in observed.items() if count != 1}
print(f'ANDROID_UNIT_XML_RECEIPT tests={sum(observed.values())} expected={len(expected)} failures={failed} errors={errors} skipped={skipped} missing={len(missing)} duplicates={len(duplicates)} unexpected={len(unexpected)}')
for label, values in [('MISSING', missing), ('UNEXPECTED', unexpected), ('DUPLICATE', duplicates)]:
    for value in sorted(values):
        print(label, '.'.join(value))
if missing or unexpected or duplicates or failed or errors or skipped or not expected:
    raise SystemExit(1)
