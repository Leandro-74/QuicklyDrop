# Spike de lifecycle do receptor

> Fase B. Mede o que o Android permite fazer quando o receptor é tocado por NFC, sem
> depender de o app estar aberto. O resultado decide a UX do receptor no produto.

## 1. Perguntas

1. O HCE é invocado com o app em foreground, em background, morto e com `force-stop`?
2. O HCE é invocado com a tela apagada e com a tela travada?
3. O processo é criado do zero? Quanto tempo ele leva até o primeiro APDU?
4. O app consegue iniciar um serviço em primeiro plano (`connectedDevice`) a partir do
   toque?
5. O broadcast `ACTION_TRANSACTION_DETECTED` chega? (Ele é documentado para Secure
   Element; o spike confirma se também vale para HCE.)
6. O grupo Wi-Fi Direct pode ser criado a partir do toque?
7. Qual a diferença entre `requireDeviceScreenOn="false"` e `"true"`?

## 2. Componentes

| Componente | Papel |
|---|---|
| `SpikeApduService` | HCE permissivo, AID `F04544524F51`, `requireDeviceScreenOn="false"` |
| `SpikeScreenOnApduService` | HCE estrito, AID `F04544524F52`, `requireDeviceScreenOn="true"` |
| `SpikeForegroundService` | FGS mínimo, tipo `connectedDevice`, notificação de teste |
| `SpikeReceiver` | Receiver de `android.nfc.action.TRANSACTION_DETECTED` |
| `SpikeReader` | Reader mode no emissor, escolhe o AID permissivo ou estrito |
| `SpikeStore` | Persiste payload e opções para sobreviver à morte do processo |
| `SpikeLog` | Logcat (tag `EasyDropSpike`) + `filesDir/spike.log` |
| `SpikePresence` | PID, idade do processo, atividade visível, FGS, tela, keyguard, Secure NFC |

O payload do spike é texto de teste. As chaves reais continuam só na memória; o spike
persiste o payload apenas para conseguir medir o cenário de processo morto.

## 3. Comandos

Instalação:

```bash
adb -s <serial> install -r lab/build/outputs/apk/debug/lab-debug.apk
```

Armar o receptor:

```bash
adb -s <serial> shell am start -n dev.leandro.easydrop.lab/.MainActivity \
  --es action spike-arm --es payload "SPIKE-RECV-1" --ez fgs true --ez group true
```

Ler no emissor (AID permissivo):

```bash
adb -s <serial> shell am start -n dev.leandro.easydrop.lab/.MainActivity \
  --es action spike-read --ez screenOn false
```

Ler no emissor (AID estrito):

```bash
adb -s <serial> shell am start -n dev.leandro.easydrop.lab/.MainActivity \
  --es action spike-read --ez screenOn true
```

Comandos auxiliares:

```bash
# estado do processo e do aparelho
adb -s <serial> shell am start -n dev.leandro.easydrop.lab/.MainActivity --es action spike-info

# baseline: FGS a partir da atividade em foreground
adb -s <serial> shell am start -n dev.leandro.easydrop.lab/.MainActivity --es action spike-fgs

# baseline: grupo P2P a partir da atividade
adb -s <serial> shell am start -n dev.leandro.easydrop.lab/.MainActivity --es action spike-group

# limpar log
adb -s <serial> shell am start -n dev.leandro.easydrop.lab/.MainActivity --es action spike-reset

# desarmar
adb -s <serial> shell am start -n dev.leandro.easydrop.lab/.MainActivity --es action spike-disarm
```

Coleta:

```bash
adb -s <serial> logcat -d -s EasyDropSpike
adb -s <serial> shell run-as dev.leandro.easydrop.lab cat files/spike.log
```

Eventos esperados no log:

| Evento | Significado |
|---|---|
| `activity.start` / `activity.stop` | Atividade visível mudou |
| `hce.onCreate` | Android criou o serviço HCE (processo pode ter nascido aqui) |
| `hce.apdu` | Um APDU chegou |
| `hce.tap` | GET DATA com payload válido |
| `hce.fgs` | Tentativa de iniciar o FGS e resultado |
| `fgs.onCreate` / `fgs.started` / `fgs.failed` | Ciclo do FGS |
| `receiver.transaction` | Broadcast de transação chegou |
| `hce.p2p` | Tentativa de `createGroup` e resultado |
| `reader.start` / `reader.payload` / `reader.stop` | Lado emissor |

## 4. Matriz

Para cada linha: armar, limpar o log, preparar o estado, tocar, coletar. Repetir com o
AID permissivo e com o AID estrito.

| # | App | Tela | Secure NFC | FGS no toque | Grupo no toque | HCE? | APDU? | FGS? | Broadcast? | Grupo? | Notas |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | foreground | ligada, destravada | desligado | sim | sim | | | | | | baseline |
| 2 | foreground | ligada, destravada | ligado | sim | sim | | | | | | |
| 3 | background (home) | ligada, destravada | desligado | sim | sim | | | | | | |
| 4 | background (home) | ligada, destravada | ligado | sim | sim | | | | | | |
| 5 | processo morto (`am kill`) | ligada, destravada | desligado | sim | sim | | | | | | |
| 6 | processo morto (`am kill`) | ligada, destravada | ligado | sim | sim | | | | | | |
| 7 | `force-stop` | ligada, destravada | desligado | sim | sim | | | | | | |
| 8 | foreground | apagada | desligado | sim | sim | | | | | | |
| 9 | foreground | apagada | ligado | sim | sim | | | | | | |
| 10 | background | apagada | desligado | sim | sim | | | | | | |
| 11 | background | apagada | ligado | sim | sim | | | | | | |
| 12 | foreground | travada | desligado | sim | sim | | | | | | |
| 13 | foreground | travada | ligado | sim | sim | | | | | | |
| 14 | background | travada | desligado | sim | sim | | | | | | |
| 15 | background | travada | ligado | sim | sim | | | | | | |

Cada linha é executada duas vezes: AID permissivo e AID estrito. A coluna "FGS no toque"
e "Grupo no toque" vêm do `--ez fgs` / `--ez group`; para isolar uma variável, desligue a
outra.

### 4.1 Preparação dos estados

```bash
# foreground: abrir a atividade
adb -s <serial> shell am start -n dev.leandro.easydrop.lab/.MainActivity

# background: voltar para a home
adb -s <serial> shell input keyevent KEYCODE_HOME

# processo morto: mata processos em background (não é o mesmo que remover dos recentes,
# mas produz o mesmo efeito de processo recriado)
adb -s <serial> shell am kill dev.leandro.easydrop.lab

# force-stop: desabilita o app e o HCE até o app ser aberto de novo
adb -s <serial> shell am force-stop dev.leandro.easydrop.lab

# tela apagada / ligada
adb -s <serial> shell input keyevent KEYCODE_POWER

# destravar (aparelho sem PIN)
adb -s <serial> shell wm dismiss-keyguard
```

Para a tela travada com PIN, o teste precisa do aparelho já travado; o desbloqueio
seguinte fica com o usuário. Não altere a senha do aparelho por adb.

## 5. Resultados

> Preencher depois da execução. Uma linha por cenário e por AID.

### 5.1 Resumo

| Pergunta | Resposta |
|---|---|
| HCE com app em foreground | |
| HCE com app em background | |
| HCE com processo morto | |
| HCE com `force-stop` | |
| HCE com tela apagada | |
| HCE com tela travada | |
| FGS a partir do HCE | |
| FGS a partir do broadcast | |
| `createGroup` a partir do HCE | |
| `requireDeviceScreenOn` faz diferença | |

### 5.2 Observações

-

## 6. Decisão de UX

Critério:

- Se o HCE e o FGS funcionam com a tela apagada/travada, o receptor pode ficar armado
  com o app fechado e o toque basta.
- Se o HCE funciona mas o FGS é bloqueado, a sessão pode viver no serviço vinculado
  enquanto o NFC estiver ativo, mas a transferência longa precisa de outra âncora
  (notificação, tile ou app aberto). Medir o tempo de vida do processo antes de decidir.
- Se o HCE só funciona com a tela ligada, o produto exige a tela ligada para receber; a
  notificação/tile passa a ser o caminho para armar.
- `requireDeviceScreenOn="true"` é mais restritivo e não protege contra um atacante com
  o aparelho na mão; só evita o gasto de energia com a tela apagada. A decisão fica com
  o resultado medido.

Decisão: _pendente de execução_.
