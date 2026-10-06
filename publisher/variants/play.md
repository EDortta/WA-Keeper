# Variante Play

Objetivo: representar exatamente o aplicativo candidato e oficial da Google Play.

Branches:
- play: candidato
- main: publicado

Identidade:
- applicationId: br.com.wanotifkeeper
- nome visível: WA Keeper

Requisitos:
- mesma identidade entre play e main;
- mesma chave de assinatura;
- configuração de release definitiva;
- sem dependência de debug keystore;
- somente recursos compatíveis com a política vigente;
- Data Safety e política de privacidade coerentes com o binário;
- artefato de publicação em Android App Bundle (AAB).

A branch play pode conter versões ainda não publicadas, mas nunca deve conter capacidades deliberadamente incompatíveis com a loja.
