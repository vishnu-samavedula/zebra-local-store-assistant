"""Use the existing local Qualcomm kit; never download an image or SDK."""
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT.parent/'zebra_npu_test'
SDK = REFERENCE/'build/qnn/sdk/qairt/2.44.0.260225'
EXPORT = ROOT/'training_kitchen/exports/masked-audio-435-v2'


def main():
    manifest = json.loads((EXPORT/'manifest.json').read_text())
    if not manifest['host_export_parity_passed']:
        raise RuntimeError('Host export parity must pass first')
    for graph in manifest['graphs']:
        folder = EXPORT/graph['name']
        target = folder/'npu'
        target.mkdir(exist_ok=True)
        cpp = target/'model.cpp'
        if not cpp.exists():
            command = ['docker','run','--rm','--pull=never','--platform','linux/amd64','--memory=8g','--cpus=4',
                '-v',str(ROOT)+':/workspace','-v',str(REFERENCE)+':/reference:ro',
                '-v',str(SDK)+':/workspace/build/qnn/sdk/qairt/2.44.0.260225:ro',
                'zebra-qnn:2.44','python3','/reference/qnn_experiment/full_model/convert_exact_norm.py',
                '--input_network',str((folder/'model.onnx').relative_to(ROOT)),
                '--output_path',str(cpp.relative_to(ROOT)),'--preserve_io','layout',
                '--input_list',str((folder/'calibration.txt').relative_to(ROOT)),
                '--act_bitwidth','16','--weights_bitwidth','8','--bias_bitwidth','32',
                '--use_per_row_quantization','--use_per_channel_quantization']
            for name in graph['inputs']:
                layout={'mel':'NCF','hidden':'NFC','padding':'NF','attention':'NONTRIVIAL','length':'NONTRIVIAL'}[name]
                command += ['--input_layout',name,layout]
            (target/'conversion-command.json').write_text(json.dumps(command,indent=2)+'\n')
            print('Converting '+graph['name'],flush=True)
            with (target/'conversion.log').open('w') as log:
                result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
            if result.returncode:
                print((target/'conversion.log').read_text()[-7000:],flush=True)
                raise SystemExit(result.returncode)
        if not (target/'model.so').exists():
            command=[sys.executable,str(REFERENCE/'qnn_experiment/build_android_model.py'),str(cpp)]
            subprocess.run(command,cwd=REFERENCE,env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1'),check=True)
        print('Built '+graph['name'],flush=True)


if __name__ == '__main__':
    main()
