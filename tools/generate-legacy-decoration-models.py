"""Create explicit pre-1.21.4 meshes without rewriting the content configuration."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / 'src/main/resources/craftengine/farmersdelight'
ASSETS = PACK / 'resourcepack/assets/farmersdelight'
SOURCE_COMMIT = '4008fac144bcf7ff140fae86b730e3301f630c34'
MODELS = ('full_tatami_mat', 'half_tatami_mat', 'canvas_rug')

def generate():
    destination = ASSETS / 'models/block/legacy'
    destination.mkdir(parents=True, exist_ok=True)
    entries = []
    for name in MODELS:
        source = ASSETS / f'models/block/{name}.json'
        model = json.loads(source.read_text(encoding='utf-8'))
        if not model.get('elements'):
            raise ValueError(f'{source} must contain its complete decoration mesh')
        # The authored source already combines the head/foot or canvas/fraying geometry.
        # Retain every element, face, texture and inherited display transform in one model.
        target = destination / f'{name}.json'
        target.write_text(json.dumps(model, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        entries.append({
            'path': target.relative_to(ASSETS).as_posix(),
            'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
            'source': source.relative_to(ASSETS).as_posix(),
            'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest()})
    manifest = {
        'generator': 'tools/generate-legacy-decoration-models.py',
        'source_repository': 'https://github.com/Nodemc-developing/Farmersdelight-Plugin-Pro',
        'source_commit': SOURCE_COMMIT,
        'attribution': 'Existing model credits and resource attribution remain unchanged; see ASSET-NOTICE.txt and the plugin NOTICE.txt.',
        'change': 'Provide explicit single-model legacy definitions; retain all existing mesh geometry and texture references without new external assets.',
        'files': entries}
    (PACK / 'ASSET-ORIGINS-LEGACY.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

if __name__ == '__main__':
    generate()
