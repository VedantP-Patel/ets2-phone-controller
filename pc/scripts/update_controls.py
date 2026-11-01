import re
import sys

profiles = [
    r"C:\Users\Vedan\OneDrive\Documents\Euro Truck Simulator 2\profiles\4E616D654173526F636B\controls.sii",
    r"C:\Users\Vedan\OneDrive\Documents\Euro Truck Simulator 2\steam_profiles\506C61796572\controls.sii"
]

for p in profiles:
    try:
        with open(p, "r", encoding="utf-8") as f:
            lines = f.readlines()

        new_lines = []
        for line in lines:
            if "mix cstm_mod_1 " in line:
                # Replace with clean joy.lb?0 to eliminate 'Joy: missing' in ETS2 UI
                line = re.sub(r'mix cstm_mod_1 `.*`', 'mix cstm_mod_1 `joy.lb?0`', line)
            elif "mix gearup " in line:
                line = re.sub(r'mix gearup `.*`', 'mix gearup `keyboard.lshift?0 | joy.rb?0 | semantical.gearup?0`', line)
            elif "mix geardown " in line:
                line = re.sub(r'mix geardown `.*`', 'mix geardown `keyboard.lctrl?0 | joy.lb?0 | semantical.geardown?0`', line)
            elif "mix gear0 " in line:
                line = re.sub(r'mix gear0 `.*`', 'mix gear0 `keyboard.slash?0 | joy.context4?0 | semantical.gear0?0`', line)
            new_lines.append(line)

        with open(p, "w", encoding="utf-8") as f:
            f.writelines(new_lines)
        print(f"Successfully updated {p}")
    except Exception as e:
        print(f"Failed to update {p}: {e}")

