"""Generate the sixteen liquid volumes from cavity dimensions and a linear fill scale."""
import json
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / 'src/main/resources/craftengine/farmersdelight_fluids/resourcepack/assets/farmersdelight/models/block/jug_fluid'

def model(level):
    height = round(1.125 + 10.5 * level / 16, 6)
    return {
        'parent': 'minecraft:block/block',
        'ambientocclusion': False,
        'textures': {'particle': 'minecraft:block/water_still', 'liquid': 'minecraft:block/water_still'},
        'elements': [{
            'from': [4.125, 1.125, 4.125], 'to': [11.875, height, 11.875],
            'shade': False,
            'faces': {face: {'uv': [0, 0, 16, 16], 'texture': '#liquid', 'tintindex': 1}
                      for face in ('down', 'up', 'north', 'south', 'west', 'east')}
        }]
    }

if __name__ == '__main__':
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for level in range(1, 17):
        target = DESTINATION / f'glass_jug_fluid_model_{level:02}.json'
        target.write_text(json.dumps(model(level), ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    shell_path = DESTINATION.parent / 'glass_jug.json'
    shell = json.loads(shell_path.read_text(encoding='utf-8'))
    for element in shell.get('elements', []):
        for face in element.get('faces', {}).values():
            face['tintindex'] = 0
    derived = DESTINATION.parent / 'glass_jug_tinted.json'
    derived.write_text(json.dumps(shell, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    invisible = DESTINATION.parent / 'fluid_tank_invisible.json'
    invisible.write_text(json.dumps({
        'parent': 'minecraft:block/block',
        'textures': {'particle': 'farmersdelight:block/jug_side'},
        'elements': []
    }, indent=2) + '\n', encoding='utf-8')
    pack = ROOT / 'src/main/resources/craftengine/farmersdelight_fluids'
    manifest_file = pack / 'ASSET-ORIGINS.json'
    manifest = json.loads(manifest_file.read_text(encoding='utf-8'))
    asset_root = pack / 'resourcepack/assets/farmersdelight'
    for entry in manifest['generated_liquid_models']['files']:
        entry['sha256'] = hashlib.sha256((asset_root / entry['path']).read_bytes()).hexdigest()
    manifest['derived'] = [{
        'path': str(derived.relative_to(asset_root)).replace('\\', '/'),
        'sha256': hashlib.sha256(derived.read_bytes()).hexdigest(),
        'source': 'models/block/glass_jug.json',
        'upstream_commit': manifest['commit'], 'license': 'MIT',
        'change': 'Add glass tint index 0 to upstream faces; original geometry and textures remain attributed to vectorwing.'
    }]
    manifest['generated_auxiliary_models'] = [{
        'path': str(invisible.relative_to(asset_root)).replace('\\', '/'),
        'sha256': hashlib.sha256(invisible.read_bytes()).hexdigest(),
        'generator': 'tools/generate-tank-liquid-models.py',
        'description': 'Empty waterlogged base; instance shell and liquid are rendered by dynamic item displays.'
    }]
    manifest_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
