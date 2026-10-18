#!/usr/bin/env python3
"""Verify built distribution boundaries and the core actually embedded in Fabric."""
from io import BytesIO
from pathlib import Path
import json
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
version = next(line.split('=', 1)[1].strip() for line in (ROOT/'gradle.properties').read_text().splitlines() if line.startswith('version='))
libs = ROOT/'build/libs'
def require(condition, message):
    if not condition:
        raise SystemExit(message)

with ZipFile(libs/f'item-parameters-core-{version}.jar') as core:
    classes = {n:core.read(n) for n in core.namelist() if n.endswith('.class')}
    require('dev/itemparams/core/ParameterValues.class' in classes, 'Core is missing ParameterValues')
    require('fabric.mod.json' not in core.namelist(), 'Standalone core must be a normal Java JAR')
    require(not any(n.startswith(('net/minecraft/', 'net/fabricmc/', 'dev/itemparams/editor/', 'dev/itemparams/fabric/')) for n in classes), 'Core contains platform/UI classes')

with ZipFile(libs/f'item-parameters-fabric-{version}.jar') as fabric:
    metadata = json.loads(fabric.read('fabric.mod.json'))
    require(metadata['id'] == 'itemparams' and metadata['version'] == version, 'Wrong Fabric metadata')
    require(not any(n.startswith(('assets/', 'data/', 'dev/itemparams/demo/', 'dev/itemparams/fabric/machine/', 'dev/itemparams/editor/')) for n in fabric.namelist()), 'Foundation contains optional demo/editor')
    nested = [entry['file'] for entry in metadata.get('jars', [])]
    found_core = False
    library_classes = set()
    for path in nested:
        with ZipFile(BytesIO(fabric.read(path))) as jar:
            names = set(jar.namelist())
            library_classes.update(names)
            if 'dev/itemparams/core/ParameterValues.class' in names:
                require(not found_core, 'Multiple embedded core implementations')
                found_core = True
                for name, data in classes.items():
                    require(jar.read(name) == data, f'Embedded core changed: {name}')
    require(found_core, 'Fabric distribution does not embed the standalone core')
    for name in ('com/ezylang/evalex/Expression.class', 'spire/math/Algebraic.class', 'scala/Option.class'):
        require(name in library_classes, f'Missing embedded runtime: {name}')

with ZipFile(libs/f'item-parameters-demo-{version}.jar') as demo:
    metadata = json.loads(demo.read('fabric.mod.json'))
    require(metadata['id'] == 'itemparams_demo' and metadata['depends']['itemparams'] == version, 'Demo version dependency mismatch')
    require('dev/itemparams/fabric/machine/ProcessorBlockEntity.class' in demo.namelist(), 'Demo machine missing')
    require('dev/itemparams/core/ParameterValues.class' not in demo.namelist(), 'Demo duplicates the core')

with ZipFile(libs/f'item-parameters-editor-{version}.jar') as editor:
    names = set(editor.namelist())
    for name in ('dev/itemparams/editor/ArrayEditor.class', 'dev/itemparams/core/ParameterValues.class', 'com/ezylang/evalex/Expression.class', 'spire/math/Algebraic.class', 'com/google/gson/Gson.class'):
        require(name in names, f'Editor missing {name}')
    require(not any(n.startswith(('net/minecraft/', 'net/fabricmc/')) for n in names), 'Editor bundles Minecraft/Fabric')

print(f'PASS: core, Fabric with {len(nested)} nested JARs, optional demo and executable editor; embedded core bytecode matches the standalone JAR.')
