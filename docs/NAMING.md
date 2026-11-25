# Nome do projeto

> Decisão: **QuicklyDrop**. O nome vale para o primeiro release e para o repositório.

## 1. Situação

O projeto nasceu como `EasyDrop`, mas o nome conflita com um projeto existente no
PyPI/GitHub (`TheLastGimbus/easydrop`, CLI para AirDrop no Linux). O conflito é de busca e
de identidade, não de código. Antes do primeiro release, o projeto foi renomeado para
`QuicklyDrop`.

Identificadores atuais:

- Repositório: `Leandro-74/QuicklyDrop`.
- `applicationId`: `dev.leandro.quicklydrop` (produto), `.lab` e `.diag`.
- Namespace dos módulos: `dev.leandro.quicklydrop`.
- Identificador de fio: `quicklydrop/1`.

O identificador de fio é estável a partir do primeiro release e não muda com o nome
comercial. Uma mudança de fio exige uma versão nova do protocolo.

## 2. O que verificar antes do release público

- PyPI, npm, GitHub, F-Droid, Google Play e registro de domínio.
- Marca e nome de pacote no F-Droid.
- Disponibilidade de `dev.leandro.quicklydrop`.
- Conflito com "Quick Drop" (Samsung) e "Quick Share" (Google) na busca.

## 3. O que não muda

- Diretório do projeto, depois da migração para `QuicklyDrop`.
- Namespace dos módulos.
- Identificador de fio: `quicklydrop/1`.
- Formato do payload NFC e do protocolo.

## 4. Migração, se o nome mudar de novo

1. Nome de exibição e ícone.
2. `applicationId` e namespace.
3. Nome do repositório e dos artefatos.
4. Metadados do F-Droid.
5. README e capturas.
6. Identificador de fio somente em uma versão nova do protocolo.
