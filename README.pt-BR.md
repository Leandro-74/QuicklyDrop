# QuicklyDrop

[English](README.md)

Compartilhamento de arquivos por proximidade entre dispositivos Android. A transferência
ocorre inteiramente entre os dois aparelhos: sem conta, sem nuvem, sem servidor.

> **Status:** `0.1.0-alpha01`. O app de produto (`:app`) implementa descoberta por NFC,
> Wi-Fi Direct, Noise NK, transferência com SHA-256, armazenamento via MediaStore e um
> serviço em primeiro plano no receptor. O protocolo e o core têm testes automatizados,
> mas o produto **ainda não foi validado em aparelhos físicos**. Trate esta versão como
> alpha.

## O que funciona

- **Receptor:** arma um grupo Wi-Fi Direct, publica SSID, senha, frequência e chave
  pública Noise pelo NFC, espera um emissor e grava em `Download/QuicklyDrop` via
  MediaStore.
- **Emissor:** recebe arquivos pelo share sheet do Android ou pelo seletor de arquivos,
  lê a tag NFC do receptor, entra no grupo, conecta por TCP e envia um ou mais arquivos.
- **Segurança:** Noise NK (`Noise_NK_25519_ChaChaPoly_SHA256`) sobre TCP, com a chave
  pública efêmera do receptor entregue pelo payload NFC.
- **Integridade:** SHA-256 por arquivo; o arquivo só é confirmado depois que o hash bate.
- **Nomes:** sanitizados contra path traversal e caracteres de controle; colisões ganham
  sufixo `(1)`, `(2)`.
- **UI do receptor:** serviço em primeiro plano com notificação de progresso e ação de
  cancelar.
- **UI do produto:** share target, tela de recebimento, tela de envio, progresso e
  resultado.
- **Cancelamento:** fechar o socket ativo interrompe leitura ou escrita bloqueada.

## Limitações conhecidas

- **Ainda não há validação em hardware.** A Fase 0 validou os transportes no laboratório,
  mas a integração do produto nunca rodou em um aparelho.
- O receptor precisa ser armado com o app aberto. O comportamento de HCE e serviço em
  primeiro plano com a tela apagada ou o app em background não foi medido.
- O emissor precisa manter a tela aberta durante a transferência; o envio roda na
  activity/ViewModel, não em um serviço em primeiro plano.
- O produto não tem BLE, LocalOnlyHotspot, fallback de LAN, Wi-Fi Aware nem UWB.
- Não há retomada, histórico, dispositivos confiáveis nem interoperabilidade com Quick
  Share.
- URIs de conteúdo cujo tamanho não pode ser resolvido são rejeitadas.
- O payload NFC e as credenciais vivem só na memória; se o processo morrer, é preciso
  armar de novo.
- É um alpha: espere falhas e reporte o modelo do aparelho, a versão do Android e o
  logcat.

## Requisitos

- Android 10 (API 29) ou superior, com NFC e Wi-Fi Direct.
- Os dois aparelhos precisam do QuicklyDrop.
- JDK 21 e Android SDK 35 para compilar.

## Arquitetura

| Módulo | Tipo | Responsabilidade |
|---|---|---|
| `:app` | App Android | UI Compose, share target, permissões, fluxo de envio, tela de recebimento |
| `:core` | Biblioteca Android | NFC, Wi-Fi Direct, sessões de envio/recebimento, MediaStore, serviço em primeiro plano |
| `:protocol` | JVM pura | Payload de descoberta, Noise NK, codec de frames, canal seguro, estado de transferência |
| `:lab` | App Android | Benchmarks de transporte e experimentos de protocolo |
| `:diag` | App Android | Diagnóstico de rádio e capacidades da Fase 0 |

O receptor arma um par de chaves Noise, cria o grupo Wi-Fi Direct, abre o servidor TCP e
publica o payload via HCE. O emissor lê o payload, entra no grupo, conecta no dono do
grupo e faz o handshake Noise. Os arquivos são enviados como `Offer`, `Accept`, `Data*`,
`End` e `Ack`; o receptor só confirma no MediaStore depois que o hash bate.

- Porta TCP: `8992`.
- AID do HCE: `F05144524F50`.
- Identificador de fio: `quicklydrop/1`.
- Protocolo: [docs/PROTOCOL.md](docs/PROTOCOL.md).
- Arquitetura: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
- Segurança: [docs/SECURITY.md](docs/SECURITY.md).

## Compilar

Requisitos: JDK 21 e Android SDK 35 (platform + build-tools).

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Se houver mais de um JDK instalado, aponte `JAVA_HOME` para o JDK 21. O build ainda não
roda no JDK 27 porque o Kotlin 2.0.21 não consegue interpretar essa versão.

### Testes

```bash
./gradlew :protocol:test :core:testDebugUnitTest :app:testDebugUnitTest \
  :lab:testDebugUnitTest :diag:testDebugUnitTest

./gradlew :app:lintDebug
```

A suíte cobre round-trip do protocolo, frames malformados, loopback do Noise, loopback de
transferência sobre TCP, cancelamento, hash divergente, sanitização de nomes, MediaStore,
permissões de P2P, estado da notificação e parsing do share intent. Ainda faltam testes
instrumentados e testes em hardware.

### APK de release

Copie `.env.example` para `.env` e preencha o caminho da keystore, a senha e o alias. A
keystore deve ficar fora do repositório.

```bash
cp .env.example .env
# edite .env
./gradlew :app:assembleRelease
```

O APK fica em `app/build/outputs/apk/release/app-release.apk`. O workflow `Release` do
GitHub compila e publica o APK quando uma tag `v*` é enviada. Ele espera os mesmos valores
nos secrets do repositório:

- `QUICKLYDROP_KEYSTORE_BASE64`
- `QUICKLYDROP_KEYSTORE_PASSWORD`
- `QUICKLYDROP_KEY_ALIAS`
- `QUICKLYDROP_KEY_PASSWORD`

Nunca faça commit de `.env` nem da keystore. Faça backup dos dois; perder a keystore
impede atualizar um app já publicado.

## Usar o app

### Receber

1. Abra o QuicklyDrop e toque em **Receive files**.
2. Mantenha o receptor armado e encoste o aparelho emissor neste aparelho.
3. O receptor cria o grupo e mostra uma notificação em primeiro plano.
4. Os arquivos aparecem em `Download/QuicklyDrop`.

### Enviar

1. Compartilhe arquivos de qualquer app e escolha QuicklyDrop, ou toque em
   **Send files** e escolha os arquivos.
2. Toque em **Scan receiver** e encoste no aparelho receptor.
3. Mantenha esta tela aberta durante a transferência.

## Laboratório

O laboratório continua testando um caminho de conexão por vez: `LAN` (mDNS + TCP), `BLE`
(BLE + GATT + hotspot), `NFC` (toque + hotspot) e `P2P` (toque + Wi-Fi Direct). Cada
execução transfere 16 MB e reporta setup e MB/s.

```bash
PKG=dev.leandro.quicklydrop.lab
adb shell am start -n $PKG/.MainActivity --es action start --es mode P2P --es role SENDER
adb shell am start -n $PKG/.MainActivity --es action start --es mode P2P --es role RECEIVER
adb shell am start -n $PKG/.MainActivity --es action stop
adb logcat -s QuicklyDropLab:I
```

O spike de lifecycle e seus comandos estão em
[docs/SPIKE_LIFECYCLE.md](docs/SPIKE_LIFECYCLE.md). Ele não foi executado.

## Diagnóstico

```bash
PKG=dev.leandro.quicklydrop.diag
adb shell am start -n $PKG/.MainActivity
adb shell am start -n $PKG/.MainActivity --es action bleadv
adb shell am start -n $PKG/.MainActivity --es action blescan
adb shell am start -n $PKG/.MainActivity --es action hotspot
adb shell am start -n $PKG/.MainActivity --es action join --es ssid "..." --es pass "..."
adb shell am start -n $PKG/.MainActivity --es action serve
adb shell am start -n $PKG/.MainActivity --es action send:192.168.0.10
adb logcat -s QuicklyDropDiag:I
```

## Validação da Fase 0

Medições de 01/11/2026, transferência de 16 MB, aparelhos a ~20 cm. Os números vêm do
laboratório, não do app de produto.

| Caminho | Setup | Throughput |
|---|---|---|
| BLE (descoberta) | 0,1–0,6 s | — |
| NFC (toque → credenciais) | 0,02 s | — |
| Wi-Fi Direct (toque → grupo) | 0,86 s (mediana) | 35–48 MB/s |
| LocalOnlyHotspot + `WifiNetworkSpecifier` | 7–10 s (diálogo do sistema) | 10–12 MB/s |
| LAN comum | 0,3–3,6 s | 1,6–5,7 MB/s |

Aparelhos de teste:

| Aparelho | Android | Observação |
|---|---|---|
| Motorola Edge 50 Ultra | 17 (SDK 37) | Wi-Fi Direct; sem serviço `wifi_aware`; UWB presente |
| Samsung Galaxy S21 (SM-G991B) | 15 (SDK 35) | Wi-Fi Direct; Wi-Fi Aware disponível |

## Roadmap

- [x] Fase 0 — validar hardware, escolher transportes, medir throughput
- [x] Protocolo v1 — payload de descoberta, Noise NK, framing, estado de transferência
- [x] Código do spike de lifecycle — execução ainda pendente
- [x] Alpha do produto — envio/recebimento com NFC + Wi-Fi Direct
- [ ] Validação do produto em hardware
- [ ] Fallback de hotspot/LAN, retomada, histórico e dispositivos confiáveis
- [ ] Interoperabilidade com Quick Share (opcional)

## Licença

[GPL-3.0](LICENSE). Projetos derivados devem permanecer abertos.
