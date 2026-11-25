<h1 align="center">QuicklyDrop</h1>

<p align="center">
  Compartilhamento de arquivos por proximidade entre aparelhos Android. Sem conta, sem nuvem, sem servidor.
</p>

<p align="center">
  <a href="https://github.com/Leandro-74/QuicklyDrop">English</a> | <a href="README.pt-BR.md">Português</a>
</p>

> ⚠️ Versão alpha `0.1.0-alpha01`. O produto ainda não foi validado em aparelhos físicos.

## Sobre

O QuicklyDrop envia arquivos entre dois aparelhos Android com um toque. O NFC apresenta o
receptor; o Wi-Fi Direct carrega os dados. A transferência fica entre os dois aparelhos.

O projeto começou como um laboratório de validação de hardware da Fase 0. O alpha do
produto é o primeiro fluxo completo de envio e recebimento construído sobre os transportes
medidos.

### Limitações conhecidas

- O produto ainda não foi validado em aparelhos físicos.
- O receptor precisa ser armado com o app aberto. Receber com a tela apagada ou em segundo
  plano não foi validado.
- O emissor precisa manter a tela aberta durante a transferência.
- BLE, fallback de hotspot e LAN, retomada, histórico, dispositivos confiáveis e
  interoperabilidade com Quick Share ficam fora desta versão.
- URIs de conteúdo cujo tamanho não pode ser resolvido são rejeitadas.

## Funcionalidades

- Enviar um ou mais arquivos pelo menu de compartilhamento do Android ou pelo seletor de
  arquivos.
- Ler o payload NFC do receptor com SSID, senha, frequência e chave Noise.
- Entrar em um grupo Wi-Fi Direct e conectar por TCP na porta `8992`.
- Proteger a sessão com Noise NK (`Noise_NK_25519_ChaChaPoly_SHA256`).
- Verificar cada arquivo com SHA-256 antes de confirmar.
- Gravar em `Download/QuicklyDrop` via MediaStore.
- Sanitizar nomes de arquivo e resolver colisões.
- Manter o receptor vivo com uma notificação em primeiro plano e ação de cancelar.
- Cancelar uma transferência fechando o socket ativo.

## Requisitos

- Android 10 (API 29) ou superior, com NFC e Wi-Fi Direct.
- Os dois aparelhos com o QuicklyDrop instalado.
- JDK 21 e Android SDK 35 para compilar.
- build-tools 35.0.0 do Android SDK.

## Instalação

### Compilar do código

```bash
git clone https://github.com/Leandro-74/QuicklyDrop.git
cd QuicklyDrop
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
```

Se houver mais de um JDK instalado, aponte `JAVA_HOME` para o JDK 21. O build não roda no
JDK 27 porque o Kotlin 2.0.21 não consegue interpretar essa versão.

### Instalar

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Testes

```bash
./gradlew :protocol:test :core:testDebugUnitTest :app:testDebugUnitTest \
  :lab:testDebugUnitTest :diag:testDebugUnitTest

./gradlew :app:lintDebug
```

A suíte cobre ida e volta do protocolo, frames malformados, loopback do Noise, loopback de
transferência sobre TCP, cancelamento, hash divergente, sanitização de nomes, MediaStore,
permissões de P2P, estado da notificação e interpretação do intent de compartilhamento.
Ainda faltam testes instrumentados e testes em hardware.

### APK de release

Copie `.env.example` para `.env` e preencha o caminho da keystore, a senha e o alias. A
keystore deve ficar fora do repositório.

```bash
cp .env.example .env
# edite .env
./gradlew :app:assembleRelease
```

O APK assinado fica em `app/build/outputs/apk/release/app-release.apk`. Sem os valores de
assinatura, o Gradle gera um APK sem assinatura e o workflow de release falha. O workflow
`Release` do GitHub compila e publica o APK quando uma tag `v*` é enviada. Ele espera os
mesmos valores nos segredos do repositório (secrets):

- `QUICKLYDROP_KEYSTORE_BASE64`
- `QUICKLYDROP_KEYSTORE_PASSWORD`
- `QUICKLYDROP_KEY_ALIAS`
- `QUICKLYDROP_KEY_PASSWORD`

Nunca faça commit de `.env` nem da keystore. Faça backup dos dois. Perder a keystore impede
atualizar um aplicativo já publicado.

## Uso

### Receber

1. Abra o QuicklyDrop e toque no botão de receber (`Receive files`).
2. Mantenha o receptor armado e encoste o aparelho emissor neste aparelho.
3. O receptor cria o grupo Wi-Fi Direct e mostra uma notificação em primeiro plano.
4. Os arquivos aparecem em `Download/QuicklyDrop`.

### Enviar

1. Compartilhe arquivos de qualquer app e escolha QuicklyDrop, ou toque no botão de enviar
   (`Send files`) e escolha os arquivos.
2. Toque em `Scan receiver` e encoste no aparelho receptor.
3. Mantenha esta tela aberta durante a transferência.

### Primeira execução

1. Conceda a permissão de Wi-Fi por proximidade. No Android 10 a 12, o app também pede
   permissão de localização, exigida pelo Wi-Fi Direct.
2. Conceda a permissão de notificação. Sem ela o receptor ainda funciona, mas a
   notificação de progresso pode não aparecer.
3. Ligue o NFC.
4. No Android 10 a 12, ligue os serviços de localização para o Wi-Fi Direct.

### Opções

- **Receber arquivos**: arma o receptor, cria o grupo Wi-Fi Direct, publica o payload NFC
  e inicia o serviço em primeiro plano.
- **Enviar arquivos**: abre o seletor de arquivos do sistema.
- **Escanear receptor**: ativa o modo leitor NFC no emissor.
- **Cancelar**: para a sessão ativa e fecha o socket.

## Estrutura do projeto

```
QuicklyDrop/
├── app/          # app de produto: UI Compose, destino de compartilhamento, permissões
├── core/         # NFC, Wi-Fi Direct, sessões, MediaStore, serviço em primeiro plano
├── protocol/     # JVM pura: payload de descoberta, Noise NK, frames, estado
├── lab/          # benchmarks de transporte e spike de ciclo de vida
├── diag/         # diagnóstico de capacidades da Fase 0
├── docs/         # documentos de protocolo, arquitetura, segurança e nome
├── README.md
├── README.pt-BR.md
└── CONTRIBUTING.md
```

## Como funciona

O receptor arma uma janela por vez. Ele gera um par de chaves Noise, cria um grupo Wi-Fi
Direct e abre um servidor TCP na porta `8992`. Publica as credenciais do grupo e a chave
pública Noise em um payload HCE com AID `F05144524F50`.

O emissor lê o payload por NFC, entra no grupo e conecta no dono do grupo. Os dois lados
fazem o handshake Noise NK e trocam frames: `Offer`, `Accept`, `Data*`, `End` e `Ack`.

O identificador de fio é `quicklydrop/1`. O receptor aceita uma sessão por janela e só
confirma cada arquivo no MediaStore depois que o SHA-256 bate. O arquivo parcial é apagado
em qualquer falha.

- Protocolo: [docs/PROTOCOL.md](docs/PROTOCOL.md)
- Arquitetura: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Segurança: [docs/SECURITY.md](docs/SECURITY.md)
- Nome: [docs/NAMING.md](docs/NAMING.md)

### Validação da Fase 0

Medições de 01/11/2026, transferência de 16 MB, aparelhos a ~20 cm. Os números vêm do
laboratório, não do app de produto.

| Caminho | Conexão | Taxa |
|---|---|---|
| BLE (descoberta) | 0,1–0,6 s | — |
| NFC (toque até credenciais) | 0,02 s | — |
| Wi-Fi Direct (toque até grupo) | 0,86 s (mediana) | 35–48 MB/s |
| LocalOnlyHotspot + `WifiNetworkSpecifier` | 7–10 s (diálogo do sistema) | 10–12 MB/s |
| LAN comum | 0,3–3,6 s | 1,6–5,7 MB/s |

Aparelhos de teste:

| Aparelho | Android | Observação |
|---|---|---|
| Motorola Edge 50 Ultra | 17 (SDK 37) | Wi-Fi Direct; sem serviço `wifi_aware`; UWB presente |
| Samsung Galaxy S21 (SM-G991B) | 15 (SDK 35) | Wi-Fi Direct; Wi-Fi Aware disponível |

### Laboratório e diagnóstico

O laboratório testa um caminho de conexão por vez: `LAN` (mDNS + TCP), `BLE` (BLE + GATT +
hotspot), `NFC` (toque + hotspot) e `P2P` (toque + Wi-Fi Direct). Cada execução transfere
16 MB e reporta o tempo de conexão e MB/s.

```bash
PKG=dev.leandro.quicklydrop.lab
adb shell am start -n $PKG/.MainActivity --es action start --es mode P2P --es role SENDER
adb shell am start -n $PKG/.MainActivity --es action start --es mode P2P --es role RECEIVER
adb shell am start -n $PKG/.MainActivity --es action stop
adb logcat -s QuicklyDropLab:I
```

O spike de lifecycle e seus comandos estão em
[docs/SPIKE_LIFECYCLE.md](docs/SPIKE_LIFECYCLE.md). Ele não foi executado.

```bash
PKG=dev.leandro.quicklydrop.diag
adb shell am start -n $PKG/.MainActivity
adb logcat -s QuicklyDropDiag:I
```

### Roadmap

- [x] Fase 0: validar hardware, escolher transportes, medir throughput
- [x] Protocolo v1: payload de descoberta, Noise NK, framing, estado de transferência
- [x] Código do spike de lifecycle: execução ainda pendente
- [x] Alpha do produto: envio e recebimento com NFC e Wi-Fi Direct
- [ ] Validação do produto em hardware
- [ ] Fallback de hotspot e LAN, retomada, histórico e dispositivos confiáveis
- [ ] Interoperabilidade com Quick Share (opcional)

## Solução de problemas

- **O NFC está desligado**: ligue o NFC e tente de novo.
- **Permissão de Wi-Fi negada**: permita dispositivos Wi-Fi por proximidade. No Android 10
  a 12, permita também a localização.
- **Serviços de localização desligados**: ligue-os para o Wi-Fi Direct no Android 10 a 12.
- **O emissor rejeita um arquivo**: o provedor não informou o tamanho. Copie o arquivo para
  o armazenamento local e compartilhe de novo.
- **O receptor para**: mantenha o app armado. Receber em segundo plano com a tela apagada
  ainda não foi validado neste alpha.
- **O build falha no JDK 27**: use o JDK 21 e ajuste `JAVA_HOME`.
- **A versão de release está sem assinatura**: preencha `.env` ou defina as quatro
  variáveis de release.
- **Perdeu a keystore**: não é possível atualizar um app instalado assinado com a chave
  antiga. Restaure o backup ou publique com um pacote novo.
- **Qualquer falha no alpha**: abra uma issue com o modelo do aparelho, a versão do Android
  e o logcat.

## Contribuição

Encontrou um bug ou tem uma ideia? Abra uma
[issue](https://github.com/Leandro-74/QuicklyDrop/issues) ou envie um pull request. Leia o
[CONTRIBUTING.md](CONTRIBUTING.md) para o fluxo de branches, testes e commits.

## Licença

Este projeto usa a licença GPL-3.0. Veja o arquivo [LICENSE](LICENSE) para os detalhes.
Projetos derivados devem permanecer abertos.
