#!/usr/bin/env python3
"""Static consistency check for the bundled demo catalog; the Java loader remains authoritative."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "fabric/src/demo/resources/itemparams-demo-catalog"
items = {}
recipes = {}
for path in sorted((root / "items").rglob("*.json")):
    doc = json.loads(path.read_text(encoding="utf-8"))
    if "item" not in doc:
        raise SystemExit(f"{path}: missing item ID")
    item_id = doc["item"]
    if item_id in items:
        raise SystemExit(f"{path}: duplicate item ID {item_id}")
    fields = doc.get("fields", [])
    ids = [field if isinstance(field, str) else field["id"] for field in fields]
    if len(ids) != len(set(ids)):
        raise SystemExit(f"{path}: duplicate field ID")
    for field in fields:
        if isinstance(field, str):
            continue
        for key in ("default", "min", "max"):
            if key in field and (isinstance(field[key], bool) or not isinstance(field[key], (int, float))):
                raise SystemExit(f"{path}: {key} for {field['id']} must be a JSON number, not {type(field[key]).__name__}")
        if "min" in field and "max" in field and field["min"] > field["max"]:
            raise SystemExit(f"{path}: min exceeds max for {field['id']}")
    items[item_id] = set(ids)

for path in sorted((root / "recipes").rglob("*.json")):
    doc = json.loads(path.read_text(encoding="utf-8"))
    recipe_id = doc["id"]
    if recipe_id in recipes:
        raise SystemExit(f"{path}: duplicate recipe ID {recipe_id}")
    inputs = doc.get("inputs", {})
    if not inputs or len(inputs) > 4:
        raise SystemExit(f"{path}: demo recipe must have 1..4 input roles")
    for role, item_id in inputs.items():
        if item_id not in items:
            raise SystemExit(f"{path}: input item {item_id} has no field definition")
    output_id = doc["output"]["item"]
    if output_id not in items:
        raise SystemExit(f"{path}: output item {output_id} has no field definition")
    if doc.get("copy_common_from") and doc["copy_common_from"] not in inputs:
        raise SystemExit(f"{path}: copy_common_from refers to unknown input role")
    output_fields = items[output_id]
    for field_id, value in doc.get("base", {}).items():
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise SystemExit(f"{path}: base value for {field_id} must be a JSON number")
    for field_id in doc.get("base", {}):
        if field_id not in output_fields:
            raise SystemExit(f"{path}: base field {field_id} is not in output schema")
    for field_id, formula in doc.get("assignments", {}).items():
        if field_id not in output_fields:
            raise SystemExit(f"{path}: assignment field {field_id} is not in output schema")
        for role, field in re.findall(r"\$\{([^:}]+)::([^}]+)\}", formula):
            if role not in inputs:
                raise SystemExit(f"{path}: formula uses unknown input role {role}")
            if field not in items[inputs[role]]:
                raise SystemExit(f"{path}: formula field {field} not defined for {inputs[role]}")
    recipes[recipe_id] = doc

# Every bundled fixture must be included in the optional demo's clean-config seed list.
demo_java = Path(__file__).resolve().parents[1] / "fabric/src/demo/java/dev/itemparams/demo/ItemParametersDemoMod.java"
source = demo_java.read_text(encoding="utf-8")
seed_block = source.split("private static final List<String> EXAMPLES = List.of(", 1)[1].split(");", 1)[0]
seeded = set(re.findall(r'"([^"\n]+\.json)"', seed_block))
resources = {str(p.relative_to(root)).replace("\\", "/") for d in ("items", "recipes") for p in (root / d).rglob("*.json")}
if seeded != resources:
    raise SystemExit(f"Demo seed mismatch: missing={sorted(resources-seeded)}, stale={sorted(seeded-resources)}")

print(f"PASS: {len(items)} item schemas, {len(recipes)} recipes, numeric JSON types valid, all input/output/field/formula references resolve, all {len(resources)} demo JSON fixtures are seeded")
