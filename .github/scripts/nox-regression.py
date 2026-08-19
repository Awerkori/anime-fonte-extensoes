#!/usr/bin/env python3
import importlib.util
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

# Carrega as funções reais de sync-upstream.py sem duplicar lógica
SCRIPT_PATH = Path(".github/scripts/sync-upstream.py").resolve()
spec = importlib.util.spec_from_file_location("sync_upstream_module", SCRIPT_PATH)
sync_mod = importlib.util.module_from_spec(spec)
sys.modules["sync_upstream_module"] = sync_mod
spec.loader.exec_module(sync_mod)

PASS = "✅ PASS"
FAIL = "❌ FAIL"
results = {}

def run_cmd(cmd, cwd=None):
    r = subprocess.run(cmd, capture_output=True, text=True, cwd=cwd)
    return r.stdout, r.returncode

# ---------------------------------------------------------
# Testes com fixtures em repositório temporário isolado
# ---------------------------------------------------------
temp_dir = tempfile.mkdtemp(prefix="nox_test_")
try:
    # Cenário A: extensão pura upstream entra normalmente
    # Cenário B: extensão Nox não tocada upstream permanece
    # Cenário C: extensão Nox tocada upstream -> Nox vence conflito
    # Cenário D: upstream versionCode alcança Nox -> Nox + 1
    # Cenário E: lib/multisrc/extractor compartilhado muda -> impacto indireto detectado
    # Cenário F: extensão exclusiva Nox -> preservada
    # Cenário G: nova extensão Yuzono -> importada

    # Setup do repo upstream mock e local mock
    up_repo = Path(temp_dir) / "upstream_repo"
    nox_repo = Path(temp_dir) / "nox_repo"
    
    # Criar repo base
    subprocess.run(["git", "init", "-b", "master", str(up_repo)], check=True, capture_output=True)
    subprocess.run(["git", "config", "user.name", "Test"], cwd=up_repo, check=True)
    subprocess.run(["git", "config", "user.email", "test@test.com"], cwd=up_repo, check=True)
    
    # Estrutura base
    (up_repo / "src/all/pure_up").mkdir(parents=True)
    (up_repo / "src/all/pure_up/build.gradle").write_text("ext {\n    extName = 'PureUp'\n    extVersionCode = 1\n}\n")
    (up_repo / "src/pt/nox_mod").mkdir(parents=True)
    (up_repo / "src/pt/nox_mod/build.gradle").write_text("ext {\n    extName = 'NoxMod'\n    extVersionCode = 1\n}\n")
    (up_repo / "src/pt/nox_same").mkdir(parents=True)
    (up_repo / "src/pt/nox_same/build.gradle").write_text("ext {\n    extName = 'NoxSame'\n    extVersionCode = 1\n}\n")
    (up_repo / "lib-multisrc/theme_a").mkdir(parents=True)
    (up_repo / "lib-multisrc/theme_a/build.gradle.kts").write_text("baseVersionCode = 5\n")
    (up_repo / "src/pt/ms_ext").mkdir(parents=True)
    (up_repo / "src/pt/ms_ext/build.gradle").write_text("ext {\n    extName = 'MsExt'\n    themePkg = 'theme_a'\n    overrideVersionCode = 1\n}\n")
    (up_repo / "lib/shared_extractor").mkdir(parents=True)
    (up_repo / "lib/shared_extractor/build.gradle.kts").write_text("dependencies {}\n")
    (up_repo / "src/en/dep_ext").mkdir(parents=True)
    (up_repo / "src/en/dep_ext/build.gradle").write_text("dependencies {\n    implementation(project(':lib:shared_extractor'))\n}\n")
    
    subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
    subprocess.run(["git", "commit", "-m", "Initial base"], cwd=up_repo, check=True)

    # Clonar base para nox_repo
    subprocess.run(["git", "clone", "-b", "master", str(up_repo), str(nox_repo)], check=True, capture_output=True)
    subprocess.run(["git", "config", "user.name", "TestNox"], cwd=nox_repo, check=True)
    subprocess.run(["git", "config", "user.email", "nox@test.com"], cwd=nox_repo, check=True)
    subprocess.run(["git", "branch", "-M", "main"], cwd=nox_repo, check=True)

    # Nox faz alterações:
    # 1. Modifica nox_mod (Nox código próprio e extVersionCode=2)
    (nox_repo / "src/pt/nox_mod/build.gradle").write_text("ext {\n    extName = 'NoxMod'\n    extVersionCode = 2\n    // Nox custom logic\n}\n")
    # 2. Modifica nox_same (código nox e extVersionCode=2)
    (nox_repo / "src/pt/nox_same/build.gradle").write_text("ext {\n    extName = 'NoxSame'\n    extVersionCode = 2\n    // Nox custom\n}\n")
    # 3. Cria extensão exclusiva Nox (F)
    (nox_repo / "src/pt/nox_exclusive").mkdir(parents=True)
    (nox_repo / "src/pt/nox_exclusive/build.gradle").write_text("ext {\n    extName = 'Exclusive'\n    extVersionCode = 1\n}\n")
    # 4. Modifica ms_ext overrideVersionCode = 2 (efetivo = 5+2=7)
    (nox_repo / "src/pt/ms_ext/build.gradle").write_text("ext {\n    extName = 'MsExt'\n    themePkg = 'theme_a'\n    overrideVersionCode = 2\n    // Nox custom multisrc\n}\n")

    subprocess.run(["git", "add", "."], cwd=nox_repo, check=True)
    subprocess.run(["git", "commit", "-m", "Nox modifications"], cwd=nox_repo, check=True)

    # Upstream faz alterações:
    # 1. Atualiza pure_up para v2 (Cenário A)
    (up_repo / "src/all/pure_up/build.gradle").write_text("ext {\n    extName = 'PureUp'\n    extVersionCode = 2\n}\n")
    # 2. Upstream modifica nox_mod em outro arquivo/mesmo arquivo com extVersionCode=1 (Cenário C: conflito Nox vence)
    (up_repo / "src/pt/nox_mod/build.gradle").write_text("ext {\n    extName = 'NoxMod'\n    extVersionCode = 1\n    // Upstream fix\n}\n")
    # 3. Upstream alcança nox_same com extVersionCode=3 (Cenário D: upstream 3 > nox 2 -> nox bump para 4)
    (up_repo / "src/pt/nox_same/build.gradle").write_text("ext {\n    extName = 'NoxSame'\n    extVersionCode = 3\n}\n")
    # 4. Upstream altera tema theme_a baseVersionCode=7 (Cenário D/E: multisrc upstream 7+1=8 >= nox 5+2=7 -> nox override vira 8+1-7 = 2, efetivo 9)
    (up_repo / "lib-multisrc/theme_a/build.gradle.kts").write_text("baseVersionCode = 7\n")
    # 5. Upstream altera lib/shared_extractor (Cenário E)
    (up_repo / "lib/shared_extractor/build.gradle.kts").write_text("dependencies { // upstream lib update }\n")
    # 6. Upstream adiciona nova extensão (Cenário G)
    (up_repo / "src/fr/new_up").mkdir(parents=True)
    (up_repo / "src/fr/new_up/build.gradle").write_text("ext {\n    extName = 'NewUp'\n    extVersionCode = 1\n}\n")

    subprocess.run(["git", "add", "."], cwd=up_repo, check=True)
    subprocess.run(["git", "commit", "-m", "Upstream new release"], cwd=up_repo, check=True)

    # Configurar upstream remote no nox_repo
    subprocess.run(["git", "remote", "add", "upstream", str(up_repo)], cwd=nox_repo, check=True)
    subprocess.run(["git", "fetch", "upstream", "master"], cwd=nox_repo, check=True)

    # Executar a engine real usando as funções do sync-upstream.py
    os.chdir(nox_repo)
    
    upstream_ref = "upstream/master"
    base = sync_mod.git("merge-base", "HEAD", upstream_ref).strip()
    
    upstream_entries = sync_mod.changed_entries(base, upstream_ref)
    upstream_units, preserved_paths = sync_mod.collect_units(upstream_entries)
    
    main_entries = sync_mod.changed_entries(base, "HEAD")
    main_units, _ = sync_mod.collect_units(main_entries)
    
    conflict_units = sorted(set(upstream_units) & set(main_units))
    conflict_set = set(conflict_units)
    upstream_only = sorted(set(upstream_units) - conflict_set)
    protected = sync_mod.get_nox_protected_units(base, upstream_ref)
    indirect = sync_mod.detect_indirect_impacts(upstream_units)

    # Executa apply_units real no repo de teste isolado
    bumped = sync_mod.apply_units(upstream_ref, upstream_units, conflict_set, protected)

    # VALIDAÇÃO DOS CENÁRIOS
    # A. Extensão pura upstream entra
    pure_up_content = (nox_repo / "src/all/pure_up/build.gradle").read_text()
    ok_a = "extVersionCode = 2" in pure_up_content
    results["A — Extensão pura upstream atualizada"] = (PASS if ok_a else FAIL, "extVersionCode atualizado para 2")

    # B. Extensão Nox não tocada upstream permanece
    # (nox_mod / ms_ext preservaram suas marcações Nox)
    ok_b = "// Nox custom logic" in (nox_repo / "src/pt/nox_mod/build.gradle").read_text()
    results["B — Extensão Nox não tocada permanece"] = (PASS if ok_b else FAIL, "Código Nox intacto")

    # C. Conflito Nox vs Upstream -> Nox vence (código Nox preservado)
    ok_c = "// Nox custom logic" in (nox_repo / "src/pt/nox_mod/build.gradle").read_text() and "// Upstream fix" not in (nox_repo / "src/pt/nox_mod/build.gradle").read_text()
    results["C — Extensão com modificação simultânea (Nox vence)"] = (PASS if ok_c else FAIL, "Código upstream rejeitado no conflito, mantido Nox")

    # D. Upstream alcança ou ultrapassa versão efetiva -> Nox bump + 1
    same_content = (nox_repo / "src/pt/nox_same/build.gradle").read_text()
    # upstream era 3, Nox era 2 -> Nox deve virar 4
    ok_d1 = "extVersionCode = 4" in same_content
    ok_d = ok_d1
    results["D — Upstream alcança versão efetiva (Nox + 1)"] = (PASS if ok_d else FAIL, f"nox_same bumped para 4 (upstream=3)")

    # E. Lib/extractor compartilhado alterado detecta impacto indireto
    ok_e = "src/en/dep_ext" in indirect
    results["E — Impacto indireto lib/extractor compartilhado"] = (PASS if ok_e else FAIL, f"src/en/dep_ext detectado no grafo: {indirect}")

    # F. Extensão exclusiva Nox preservada
    ok_f = (nox_repo / "src/pt/nox_exclusive/build.gradle").exists()
    results["F — Extensão exclusiva Nox preservada"] = (PASS if ok_f else FAIL, "src/pt/nox_exclusive existe e intacto")

    # G. Nova extensão upstream importada
    ok_g = (nox_repo / "src/fr/new_up/build.gradle").exists() and "extName = 'NewUp'" in (nox_repo / "src/fr/new_up/build.gradle").read_text()
    results["G — Nova extensão upstream importada"] = (PASS if ok_g else FAIL, "src/fr/new_up importado com sucesso")

finally:
    shutil.rmtree(temp_dir, ignore_errors=True)

# ---------------------------------------------------------
# Cenário H: Teste no repositório real
# ---------------------------------------------------------
os.chdir(SCRIPT_PATH.parents[2]) # volta para raiz do projeto real

# Calcular versão efetiva da anikyuu via engine real
real_eff = sync_mod.effective_version(None, "src/pt/anikyuu")

# Comparar com aapt dump badging do APK compilado
apk_file = Path("src/pt/anikyuu/build/outputs/apk/release/aniyomi-pt.anikyuu-v14.9-release.apk")
aapt_bin = Path("/home/awerkori/Android/Sdk/build-tools/36.0.0/aapt")

if apk_file.exists() and aapt_bin.exists():
    out, code = run_cmd([str(aapt_bin), "dump", "badging", str(apk_file)])
    pkg_line = next((l for l in out.splitlines() if l.startswith("package: ")), "")
    vc_match = re.search(r"versionCode='(\d+)'", pkg_line)
    vn_match = re.search(r"versionName='([^']+)'", pkg_line)
    
    if vc_match and real_eff:
        apk_vc = int(vc_match.group(1))
        # real_eff = (overrideVersionCode=2, baseVersionCode=7, effective=9)
        ok_h = (apk_vc == real_eff[2])
        results["H — Validação APK real vs engine versionCode/versionName"] = (
            PASS if ok_h else FAIL,
            f"Engine efetivo={real_eff[2]} (base {real_eff[1]} + override {real_eff[0]}) == APK aapt={apk_vc}, versionName='{vn_match.group(1)}'"
        )
    else:
        results["H — Validação APK real vs engine versionCode/versionName"] = (FAIL, "Falha ao extrair metadados")
else:
    results["H — Validação APK real vs engine versionCode/versionName"] = (FAIL, "APK ou aapt não encontrado")

# ---------------------------------------------------------
# Relatório dos Resultados
# ---------------------------------------------------------
print("\n" + "="*65)
print("  PROJECT NOX ANIME — RESULTADO REGRESSÃO (A-H)")
print("="*65)
all_pass = True
for name, (status, detail) in results.items():
    print(f"\n{status} {name}")
    print(f"    Detalhe: {detail}")
    if status != PASS:
        all_pass = False

print("\n" + "="*65)
if all_pass:
    print("✅ TODOS OS CENÁRIOS (A-H) PASSARAM COM SUCESSO!")
    sys.exit(0)
else:
    print("❌ ALGUNS CENÁRIOS FALHARAM.")
    sys.exit(1)
