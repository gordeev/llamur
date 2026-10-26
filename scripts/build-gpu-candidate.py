#!/usr/bin/env python3
"""Source-built GPU research tools, isolated from the installed/CPU test APK."""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import platform
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend', choices=['vulkan','opencl','both'], default='both')
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    if not 1 <= args.jobs <= 16: parser.error('Jobs outside1..16')
    deps = ROOT/'.tools/gpu-deps'
    lock = json.loads((ROOT/'bench/gpu-dependencies.lock.json').read_text())
    for dep in lock['dependencies']:
        archive=deps/(dep['id']+'.tar.gz');deps.mkdir(parents=True,exist_ok=True)
        if not archive.exists():
            url=dep['repository'].replace('https://github.com/','https://codeload.github.com/')+'/tar.gz/'+dep['commit']
            with urllib.request.urlopen(url,timeout=60) as response: archive.write_bytes(response.read())
        if hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()!=dep['archive_sha256']:
            raise RuntimeError('Dependency archive mismatch: '+dep['id'])
        directory=deps/(dep['repository'].rsplit('/',1)[-1]+'-'+dep['commit'])
        if not directory.exists():
            with tarfile.open(archive) as source: source.extractall(deps,filter='data')
        # Existing generated source must still match every locked archive member.
        with tarfile.open(archive) as source:
            for item in source.getmembers():
                path=(deps/item.name).resolve()
                if not path.is_relative_to(deps.resolve()) or item.issym() or item.islnk(): raise RuntimeError('Unsafe dependency archive')
                if item.isfile() and path.read_bytes()!=source.extractfile(item).read(): raise RuntimeError('Dependency source changed: '+item.name)
        dep['directory']=str(directory)
    directories={dep['id']:dep['directory'] for dep in lock['dependencies']}
    ndk=pathlib.Path(os.environ.get('ANDROID_NDK_ROOT',ROOT/'.tools/android-ndk-r28c')).resolve()
    cmake=pathlib.Path(os.environ.get('MOTOAI_CMAKE_DIR',ROOT/'.tools/cmake-3.31.6'))/'bin/cmake'
    ninja=shutil.which('ninja')
    if not ninja: raise RuntimeError('Ninja required')
    host={'Darwin':'darwin-x86_64','Linux':'linux-x86_64','Windows':'windows-x86_64'}[platform.system()]
    out=ROOT/'bench/results'/('gpu-build-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));out.mkdir()
    build=ROOT/'build'/('android-selective-'+args.backend)
    report=dict(passed=False,backend=args.backend,build_directory=str(build.relative_to(ROOT)),commands=[],dependencies=lock,
                scope='Build only, no app-UID/kernel/model/quality/performance acceptance; source kernels only, no Qualcomm binary library')
    def snapshot():
        paths=sorted(p for p in (ROOT/'native-runtime').rglob('*') if p.is_file())
        return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    report['native_sources_before']=snapshot()
    def run(command):
        command=[str(value) for value in command];report['commands'].append(command)
        with (out/'build.log').open('a') as log: subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True)
    android=['-G','Ninja','-DCMAKE_MAKE_PROGRAM='+ninja,'-DCMAKE_BUILD_TYPE=Release',
             '-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),'-DANDROID_ABI=arm64-v8a',
             '-DANDROID_PLATFORM=android-28','-DANDROID_STL=c++_shared']
    try:
        run(['python3','scripts/prepare-cpu-variant.py','f32-recurrent-selective-backends'])
        run([cmake,'-S',directories['spirv-headers'],'-B','build/gpu-spirv-headers','-DCMAKE_INSTALL_PREFIX='+str(deps/'install'),
             '-DSPIRV_HEADERS_ENABLE_TESTS=OFF','-DSPIRV_HEADERS_ENABLE_EXAMPLES=OFF'])
        run([cmake,'--install','build/gpu-spirv-headers'])
        run([cmake,'-S','native-runtime/tools/opencl-link-dependency','-B','build/android-opencl-link',*android,
             '-DMOTOAI_OPENCL_LOADER_SOURCE='+directories['opencl-loader'],
             '-DOPENCL_ICD_LOADER_HEADERS_DIR='+directories['opencl-headers']])
        run([cmake,'--build','build/android-opencl-link','--parallel',args.jobs])
        flags=['-DMOTOAI_LLAMA_SOURCE='+str(ROOT/'.tools/llama-gpu-selective'),'-DMOTOAI_RUNTIME_VARIANT=f32-recurrent-selective-backends',
               '-DGGML_NATIVE=OFF','-DGGML_CPU_KLEIDIAI=OFF','-DGGML_OPENMP=OFF','-DGGML_LLAMAFILE=OFF',
               '-DGGML_BACKEND_DL=ON','-DGGML_CPU_ALL_VARIANTS=ON',
               '-DGGML_VULKAN='+('ON' if args.backend in ('vulkan','both') else 'OFF'),
               '-DGGML_OPENCL='+('ON' if args.backend in ('opencl','both') else 'OFF'),
               '-DVulkan_INCLUDE_DIR='+directories['vulkan-headers']+'/include',
               '-DVulkan_LIBRARY='+str(ndk/'toolchains/llvm/prebuilt'/host/'sysroot/usr/lib/aarch64-linux-android/28/libvulkan.so'),
               '-DVulkan_GLSLC_EXECUTABLE='+str(ndk/'shader-tools'/host/'glslc'),
               '-DSPIRV-Headers_DIR='+str(deps/'install/share/cmake/SPIRV-Headers'),
               '-DCMAKE_CXX_FLAGS=-I'+str(deps/'install/include'),
               '-DGGML_OPENCL_EMBED_KERNELS=ON','-DGGML_OPENCL_USE_ADRENO_KERNELS=ON','-DGGML_OPENCL_USE_ADRENO_BIN_KERNELS=OFF',
               '-DOpenCL_INCLUDE_DIR='+directories['opencl-headers'],
               '-DOpenCL_LIBRARY='+str(ROOT/'build/android-opencl-link/loader/libOpenCL.so')]
        run([cmake,'-S','native-runtime','-B',build,*android,*flags])
        run([cmake,'--build',build,'--target','motoai-backend-ops','motoai-reference','--parallel',args.jobs])
        artifacts=[*build.rglob('*.so'),build/'motoai-backend-ops',build/'motoai-reference']
        report['artifacts']={str(p.relative_to(ROOT)):hashlib.file_digest(p.open('rb'),'sha256').hexdigest() for p in artifacts}
        report['native_sources_after']=snapshot()
        if report['native_sources_after']!=report['native_sources_before']: raise RuntimeError('Source changed during build')
        report['passed']=True
    except Exception as error: report['error']=str(error)
    finally:
        (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
        print('Results:',out.relative_to(ROOT),flush=True)
    return 0 if report['passed'] else 1


if __name__=='__main__': raise SystemExit(main())
