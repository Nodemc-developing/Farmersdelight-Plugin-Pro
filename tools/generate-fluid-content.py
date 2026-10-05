"""Generate tank items and blocks. Workstation recipes live in the separate fluid_recipes.yml file."""
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'src/main/resources/craftengine/farmersdelight_fluids/configuration/fluid_tank.yml'
ITEMS = {}

class LiteralDumper(yaml.SafeDumper):
    def ignore_aliases(self, data):
        return True

def model(path, index=None):
    value = {'type': 'minecraft:model', 'path': path}
    if index is not None:
        value['tints'] = [{'type': 'minecraft:custom_model_data', 'index': index, 'default': 16777215}]
        if index == 1:
            value['tints'].append(value['tints'][0].copy())
    return value

def tank(item_id, name, shell_path, glass):
    prefix = item_id + '_visual'
    shell = model(shell_path, 0 if glass else None)
    legacy_shell = 'farmersdelight:block/glass_jug' if glass else shell_path
    ITEMS[prefix + '/empty'] = {'material': 'minecraft:paper', 'model': shell, 'legacy_model': {'path': legacy_shell}}
    ITEMS[prefix + '/waterlogged/empty'] = {'material': 'minecraft:paper', 'model': shell, 'legacy_model': {'path': legacy_shell}}
    for level in range(1, 17):
        fluid = model(f'farmersdelight:block/jug_fluid/glass_jug_fluid_model_{level:02}', 1)
        legacy_path = f'farmersdelight:block/jug_legacy/{"glass_jug" if glass else "jug"}_level_{level:02}'
        for suffix in (f'/level_{level:02}', f'/waterlogged/level_{level:02}'):
            ITEMS[prefix + suffix] = {
                'material': 'minecraft:paper',
                'model': {'type': 'minecraft:composite', 'models': [shell, fluid]},
                'legacy_model': {'path': legacy_path}}
    appearances, variants = {}, {}
    for facing, yaw in [('north', 0), ('east', 90), ('south', 180), ('west', 270)]:
        for wet in [False, True]:
            appearance = {'transparent': glass, 'entity_renderer': {
                'type': 'item_display', 'item': prefix + '/empty', 'yaw': yaw,
                'tint_source': {'type': 'fluidcore:tank'}}}
            if wet:
                appearance.update({'auto_state': 'waterlogged_non_tintable_leaves', 'model': {'path': 'farmersdelight:block/fluid_tank_invisible', 'y': yaw}})
            else:
                appearance['state'] = 'chorus_plant[down=true,east=false,north=false,south=false,up=false,west=false]'
            key = facing + ('_waterlogged' if wet else '')
            appearances[key] = appearance
            variants[f'facing={facing},waterlogged={str(wet).lower()}'] = {'appearance': key}
    ITEMS[item_id] = {
        'material': 'minecraft:paper',
        'data': {'item_name': f'<!i><white>{name}', 'lore': [
            '<!i><gray>容量：16 桶 / 16000 mB', '<!i><gray>桶或瓶右键转移；空手打开容器',
            '<!i><gray>输入输出支持漏斗；拆除保留流体' + ('；染料右键染色' if glass else '')], 'max_stack_size': 1},
        'model': shell,
        'legacy_model': {'path': legacy_shell},
        'settings': {'fluidcore:container': {'capacity': 16000}},
        'behavior': [
            {'type': 'fluidcore:container', 'item-model': prefix},
            {'type': 'block_item', 'block': {
                'settings': {'hardness': 2.0, 'resistance': 6.0, 'is_suffocating': False, 'is_redstone_conductor': False,
                    'sounds': {'break': 'minecraft:block.glass.break' if glass else 'minecraft:block.copper.break',
                               'place': 'minecraft:block.glass.place' if glass else 'minecraft:block.copper.place'}},
                'behavior': {'type': 'fluidcore:tank', 'capacity': 16000, 'transparent': glass, 'dyeable': glass, 'item-model': prefix, 'menu': {'layout': 'jug', 'theme': 'plain'}},
                'loot': {'pools': [{'rolls': 1, 'entries': [{'type': 'item', 'item': item_id,
                    'functions': [{'type': 'set_count', 'count': 1}, {'type': 'fluidcore:preserve_tank'}]}]}]},
                'states': {'properties': {'facing': {'type': 'horizontal_direction', 'default': 'north'},
                                          'waterlogged': {'type': 'boolean', 'default': False}},
                           'appearances': appearances, 'variants': variants}}}]}

if __name__ == '__main__':
    tank('farmersdelight:fluid_tank', '玻璃流体储罐', 'farmersdelight:block/glass_jug_tinted', True)
    tank('farmersdelight:fluid_jug', '流体储罐', 'farmersdelight:block/jug', False)
    TARGET.write_text(yaml.dump({'items': ITEMS}, Dumper=LiteralDumper, allow_unicode=True, sort_keys=False), encoding='utf-8')
