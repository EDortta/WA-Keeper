# Benchmark ASR — pré-processamento

- Android: SM-A175F
- Modelo Android: sherpa-onnx Whisper small INT8.
- Referência: faster-whisper small no devel3 sobre o áudio original.
- O objetivo é reduzir tempo sem piorar a qualidade em mais de 3 pontos percentuais de WER.

## Resumo

| Variante | WER médio | CER médio | Duração média | RTF vs áudio original | Tempo total |
|---|---:|---:|---:|---:|---:|
| Original | 15.7% | 6.9% | 99.9% | 1.58x | 262.0s |
| Silêncio removido | 15.8% | 8.8% | 91.2% | 1.70x | 257.5s |
| 1,15x | 12.9% | 5.5% | 86.8% | 2.07x | 501.4s |
| 1,25x | 11.2% | 5.9% | 79.7% | 3.48x | 484.5s |
| Silêncio removido + 1,15x | 9.4% | 4.6% | 79.3% | 1.52x | 264.3s |

## Recomendação automática

Silêncio removido: WER médio 15.8%, tempo total 257.5s.

## Amostras

### pequena — 3.6s

Remetente: Luis Fernando Calegari

**Referência / devel3**

Que dia você tá de boa, mano? Pra dar um pulo lá.

**Original** (duração 3.6s, WER 16.7%, tempo 8.1s)

Que dia você está de boa, mano? Para dar um pulo lá.

**Silêncio removido** (duração 3.5s, WER 25.0%, tempo 9.7s)

Que dia você está de boa, mano? Para não dar um pulo lá.

**1,15x** (duração 3.1s, WER 16.7%, tempo 8.0s)

Que dia você está de boa, mano? Para dar um pulo lá.

**1,25x** (duração 2.9s, WER 0.0%, tempo 19.5s)

Que dia você tá de boa, mano? Pra dar um pulo lá.

**Silêncio removido + 1,15x** (duração 3.1s, WER 0.0%, tempo 7.7s)

Que dia você tá de boa, mano? Pra dar um pulo lá.

### media — 33.8s

Remetente: Ricardo Lucena

**Referência / devel3**

Aqui no WhatsApp eu estava só com esse relatório da Comissão de Reforma do Estatuto, depois eu vou chegar em casa e vou te mandar o do Diacons que eu montei e depois vou te mandar o esboço também desse de projeto de missões regionais, que na verdade tecnicamente seria um pouquinho daquilo que eu vi na faculdade, seria as missões urbanas ali, mais ou menos nesse sentido aí, tentar aplicar esse contexto.

**Original** (duração 33.8s, WER 15.1%, tempo 38.9s)

Aqui no WhatsApp eu estava só com esse relatório aí da comissão de reforma do estatuto. Depois eu vou chegar em casa, vou te mandar o do Diacons que eu montei e depois eu vou te mandar os bolso também desse de projeto de missões regionais. Na verdade, tecnicamente seria um pouquinho daquilo que eu vi na faculdade, seriam as missões urbanas ali, mas o mesmo nesse sentido, tentar aplicar esse contexto.

**Silêncio removido** (duração 28.4s, WER 6.8%, tempo 36.5s)

Aqui no WhatsApp eu estava só com esse relatório aí da comissão de reforma do estatuto. Depois eu vou chegar em casa, vou te mandar o do Diacons que eu montei e depois eu vou te mandar o esboço também desse de projeto de missões regionais. Na verdade, tecnicamente seria um pouquinho daquilo que eu vi na faculdade, seriam as missões urbanas ali. mais ou menos nesse sentido aí, tentar aplicar esse contexto.

**1,15x** (duração 29.4s, WER 5.5%, tempo 38.3s)

Aqui no WhatsApp eu estava só com essa relatória da comissão de reforma do estatuto. Depois eu vou chegar em casa, vou te mandar o do Diacons que eu montei e depois vou te mandar o esboço também desse de projeto de missões regionais. Na verdade, tecnicamente seria um pouquinho daquilo que eu vi na faculdade, seria as missões urbanas ali. mais ou menos nesse sentido aí tentar aplicar esse contexto

**1,25x** (duração 27.0s, WER 15.1%, tempo 90.4s)

Aqui no WhatsApp eu estava só com esse relatório da comissão de reforma do estatuto. Depois eu vou chegar em casa e vou te mandar o do Diacons que eu montei e depois vou te mandar os bolso também desse de projeto de missões regionais. Na verdade, tecnicamente seria um pouquinho daquilo que eu vi na faculdade, serias missões urbanas ali, mas ao mesmo tempo nesse sentido. aplicar esse contexto.

**Silêncio removido + 1,15x** (duração 24.7s, WER 12.3%, tempo 35.7s)

Aqui no WhatsApp eu estava só com esse relatório da comissão de reforma do estatuto. Depois eu vou chegar em casa, vou te mandar o do Diacons que eu montei e depois vou te mandar o esboço também desse de projeto de missões regionais. Na verdade, tecnicamente seria um pouquinho daquilo que eu vi na faculdade, serias missões urbanas ali, mas ao mesmo tempo esse sentido aí, tentar aplicar esse contexto.

### longa — 159.6s

Remetente: Ricardo Lucena

**Referência / devel3**

Tá, vamos tentar explicar que eu fui ouvindo seu áudio lá, seu podcast de 8 minutos e agora eu fui respondendo em baixo, tá? Você fala da questão do objetivo da SET, da IBS, né? Aí eu usei com você falando que você tinha que participar das reuniões, entendeu? Sua presença lá seria importante, mas tudo bem, entendo lá que você não tá impossibilitado. Essa é a primeira ponta. Segundo, você fala da questão das igrejas com o percentual de pessoas que saem, né? Se movimentam nas igrejas. Aí eu falei que nós estamos com um problema aqui de adolescentes e jovens na igreja. Nós temos um alacuno, nós temos crianças, juniores ali, crianças e tal, e adultos e nós não temos jovens aqui na igreja, porque você tem ideia nesse sentido. É outro ponto do áudio que você fala. Aí eu complemento dizendo que nós estamos com a grande evasão também de adultos na IBD e esses adultos pais não trazem seus filhos pra IBD. Então, além da evasão de jovens que nós temos na igreja de uma forma geral, nós estamos com a evasão de adultos e crianças na IBD, porque os pais não trazem os abençoadinhos pra participar, entendeu? Nós até temos um projeto com juniores e adolescentes aí na sexta-feira, ele tem um propósito mais evangelístico no que necessariamente de ensino semelhante a IBD. Então, é por isso que nós estamos tentando a questão do PEC pra que nós possamos primeiro entender a nossa cultura aqui, o que que tá acontecendo, a ponta a gente tentar planejar alguma coisa pra que realmente venha a trazer frutos aí no sentido da educação que está. Esse é outro ponto. Aí lá também no áudio você fala sobre a questão do prédio que ele tem algumas questões a serem reformadas e tal. A última vez que o pastor Gilberto mandou pra mim, parece que tinha uns forros, que tinham caído de uma sala e tudo mais e tal, mas mesmo nesse sentido aí que foi passado. Mas o pastor Gilberto é meio sistemático. Então, eu começo conversar, olha que eu vejo que ele bloqueia, eu não falo mais nada também, entendeu? Às vezes a resposta deve é mais sica em algumas situações, mas eu não consigo com as questões. Mas aí foi onde eu argumentei, eu falei assim, eu não posso ir lá, você falou de criar a comissão pra analisar de fato como tá o prédio. Então, eu falei, eu não posso participar porque eu sou idealista, enquanto eles vão enxergar que não dá, eu vou enxergar que é possível. Da mesma forma como eu estou enxergando que é possível usar o seminário pra alguma situação. É nesse sentido aí que eu fui respondendo seus áudios anteriores, tá bom?

**Original** (duração 159.6s, WER 15.4%, tempo 215.0s)

Vamos tentar explicar, eu fui ouvir seu áudio lá, seu podcast de 8 minutos e agora eu fui responder embaixo. Você fala da questão do objetivo da sete, da AibaS. Aí eu usei com você falando que você tinha que participar das reuniões, entendeu? Sua presença lá seria importante, mas tudo bem, entendo lá que você não está impossibilitado. Esse é o primeiro ponto. Segundo, você fala da questão das igrejas com o percentual de pessoas que saem, se movimentam nas igrejas. Aí eu falei que nós estamos com um problema aqui de adolescentes e jovens na igreja. Nós temos o alacuno, nós temos crianças, juniores ali, crianças e adultos e nós não temos jovens aqui na igreja, porque você tem ideia nesse sentido. É outro ponto do áudio que você fala. Aí eu complemento dizendo que nós estamos com a grande evasão também de adultos na IBD e esses adultos pais não trazem seus filhos para a IBD. Então, além da evasão de jovens que nós temos na igreja de uma forma geral, nós estamos com a evasão de adultos e crianças na IBD porque os pais não trazem os abençoadinhos para participar. Nós até temos um projeto com juniores adolescentes aí. na sexta-feira, mas ele tem um propósito mais evangelístico no que necessariamente de ensino semelhante a IBD. Então, é por isso que nós estamos tentando a questão do PEC para que nós possamos primeiro entender a nossa cultura aqui, o que está acontecendo, aponta a gente tentar planejar alguma coisa para que realmente também a trazer frutos aí no sentido da educação cristal esse é outro ponto aí lá também no no áudio você fala sobre a questão do prédio que ele tem algumas questões a serem reformadas tal a última vez que o pastor de Bertha mandou para mim parece que tinha uns forros que tinham caído de uma sala e tudo mais e tal né mas mesmo nesse sentido aí que foi o que foi passado mas o pastor de Bertha ele é assim ele é meio sistemático então assim eu começo e eu vejo que ele bloqueia e não fala mais nada também, entendeu? Às vezes a resposta dele é mais seca em algumas situações, então eu não prosso com as questões. Mas aí eu falei onde eu argumentei, eu falei assim, eu não posso ir lá, você falou da Criar comissão para analisar de fato como está o prédio. Então eu falei, eu não posso participar porque eu sou idealista, enquanto eles vão enxergar que não dá, eu vou enxergar que é possível. Da mesma forma como estou enxergando que é possível. possível usar o seminário para alguma situação. É nesse sentido que eu fui respondendo seus áudios anteriores.

**Silêncio removido** (duração 147.0s, WER 15.6%, tempo 211.3s)

Vamos tentar explicar que eu fui ouvindo seu áudio lá, seu podcast de 8 minutos e agora eu fui responder em baixo. Você fala da questão do objetivo da SET, da AibaS. Aí eu usuei com você falando que você tinha que participar das reuniões, entendeu? Só para a experiência lá seria importante, mas tudo bem, entendo lá que você não está impossibilitado. Esse é o primeiro ponto. Segundo, você fala da questão da igrejas com o percentual de pessoas que saem, se movimentam nas igrejas. Aí eu falei que nós estamos com um problema aqui de adolescentes e jovens. Na igreja nós temos um alacuno, nós temos crianças, juniores ali, crianças e adultos e nós não temos os jovens aqui na igreja, para você ter ideia nesse sentido. É outro ponto do áudio que você faz. Aí eu complemento dizendo que nós estamos com a grande vazão também de adultos na IBD e esse esses adultos pais não trazem seus filhos para a IBD. Então, além da invasão de jovens que nós temos na igreja, de uma forma geral, nós estamos com a invasão de adultos e crianças na IBD porque os pais não trazem os abençoadinhos para participar. Nós até temos um projeto com juniores adolescentes na sexta-feira, mas ele tem um propósito mais evangelístico no que necessariamente ensino semelhante a IBD. Então é por isso que nós estamos tentando a questão do PEC para que nós possamos primeiro entender a nossa cultura aqui, o que está acontecendo, a ponta a gente tentar planejar alguma coisa para que realmente venha a trazer frutos aí no sentido da educação cristal. Esse é o outro ponto. Aí lá também no áudio você fala sobre a questão do prédio, tem algumas questões a serem reformadas. A última vez que o pastor Gilberto mandou para mim parece que tinha uns forvos que tinham caído de uma sala e tudo mais e tal mas mesmo nesse sentido aí que foi o que foi passar. Mas o pastor Gilberto ele é assim ele é meio sistemático então assim eu começo conversar e vejo que ele bloqueia eu não falo mais nada também entendeu. Às vezes a resposta deve mais seca em algumas situações então eu não consigo com as questões. Mas aí aí onde eu argumentei, eu não posso ir lá, você falou da CREAL COMISSÃO pra analisar de fato como está o prédio. Então eu falei, eu não posso participar porque eu sou idealista, enquanto eles vão enxergar que não dá, eu vou enxergar que é possível. Da mesma forma como eu estou enxergando que é possível usar o seminário pra alguma situação, tá? Nesse sentido aí que eu fui respondendo seus áudios anteriores, tá bom?

**1,15x** (duração 138.8s, WER 16.5%, tempo 455.0s)

Vamos tentar explicar que eu fui ouvir seu áudio lá, seu podcast de 8 minutos e agora eu fui responder embaixo. Você fala da questão do objetivo da SET, da AÍBAS. Aí eu usei com você falando que você tinha que participar das reuniões, entendeu? Só para a presença lá seria importante, mas tudo bem, entendo lá que você não está impossibilitado. Esse é o primeiro ponto. Segundo, você fala da questão das igrejas com um percentual de pessoas que saem, se movimentam nas igrejas. Aí eu falei que nós estamos com um problema aqui de adolescentes e jovens na igreja. Nós temos um alacuno, nós temos crianças, júniores ali, crianças e adultos e nós não temos jovens aqui na igreja. Então você tem ideia nesse sentido. É outro ponto do áudio que você fala. Aí eu complemento dizendo que nós estamos com a grande evasão também de adultos na IBD e esses adultos pais não trazem seus e os seus auxílios para IBD. Então, além da invasão de jovens que nós temos na igreja de uma forma geral, nós estamos com a invasão de adultos e crianças na IBD porque os pais não trazem os abençoadinhos para participar. Nós até temos um projeto com juniores adolescentes na sexta-feira, mas ele tem um propósito mais evangelístico no que necessariamente de ensino semelhante a IBD. Então, é por isso que nós estamos tentando a questão do PEC, para que nós possamos entender a nossa cultura aqui, o que está acontecendo, a ponta a gente tentar planejar alguma coisa para que realmente venha trazer frutos no sentido da educação que está. Esse é outro ponto. Aí lá também no áudio você fala sobre a questão do prédio, que ele tem algumas questões a serem reformadas. A última vez que o pastor do Bairro mandou para mim, parece que tinha uns forros de um caíra, de uma sala e tudo mais e tal, mas na mesma sentida que foi passada. Mas o pessoal de Bertha é meio sistemático, então eu começo conversar, e quando eu vejo que ele bloqueia eu não falo mais nada também, entendeu? Às vezes a resposta dele é mais seca em algumas situações, então eu não prosso com as questões. Mas aí foi onde eu argumentei, eu falei assim, eu não posso ir lá, você falou da CREAL COMISSION para analisar de fato como está o PREV. Então eu falei, eu não posso participado que eu sou idealista enquanto eles vão enxergar que não dá eu vou enxergar que é possível da mesma forma como estou enxergando que é possível usar o seminário para alguma situação tá é nesse sentido aí que eu fui respondendo os seus seus áudios anteriores tá bom

**1,25x** (duração 127.6s, WER 18.4%, tempo 374.6s)

Vamos tentar explicar que eu fui ouvir seu áudio lá, seu podcast de 8 minutos e agora eu fui responder embaixo. Você fala da questão do objetivo da sete, da aiba. Aí eu usei com você falando que você tinha que participar das reuniões, entendeu? Só para a presença lá ser importante, mas tudo bem, entendo lá que você não está impossibilitado. Esse é o primeiro ponto. Segundo, você fala da questão das igrejas com o percentual de pessoas que saem, né? que se apresentam nas igrejas. Aí eu falei que nós estamos com um problema aqui de adolescentes e jovens na igreja. Nós temos um alacuno, nós temos crianças, júniores ali, crianças e adultos e nós não temos jovens aqui na igreja. Você tem ideia nesse sentido. É outro ponto do áudio que você fala. Aí eu complemento dizendo que nós estamos com a grande evasão também de adultos na IBD e esses adultos pais não trazem seus filhos para IBD. Então, além da evasão de jovens que nós temos na da igreja, de uma forma geral, nós estamos com a invasão de adultos e crianças na IBD, porque os pais não trazem os abençoadinhos para participar. Nós até temos um projeto com juniores adolescentes na sexta-feira, mas ele tem um propósito mais evangelístico no que necessariamente ensina o semenete a IBD. Então, é por isso que nós estamos tentando a questão do PEC para que nós possamos entender nossa cultura aqui, o que que está acontecendo, a ponta a gente tentar planejar alguma coisa para que realmente vêm trazer frutos aí no sentido da educação que está. Esse é o outro ponto. Aí lá também no áudio você faça sobre a questão do prédio, que ele tem algumas questões a ser reformada e tal. A última vez que o pastor de Bertha mandou para mim, parece que tinha uns forvos que tinham caído de uma sala e tudo mais e tal, mas mesmo nesse sentido aí que foi passado. Mas o pastor de Bertha ele é meio sistemático, então assim, eu começo com a hora que eu vejo que ele bloqueia não fala mais nada também entendeu às vezes a resposta dele é mais seca em algumas situações então eu não consigo com com as questões mas aí a fã de argumento eu não posso ir lá você pode criar a comissão é para analisar de fato como está o prédio eu não posso participar porque eu sou idealista enquanto eles vão enxergar que não dá eu vou enxergar que é possível da mesma forma como estou enxergando que é possível usar o seminário para alguma situação tá nesse sentido é que eu fui respondendo os seus Seus áudios anteriores.

**Silêncio removido + 1,15x** (duração 127.8s, WER 16.0%, tempo 221.0s)

Vamos tentar explicar que eu fui ouvir seu áudio lá, seu podcast de 8 minutos e agora eu fui responder em baixo. Você fala da questão do objetivo da SET, da Aivas. Aí eu usei com você falando que você tinha que participar das reuniões, entendeu? Só para a presença lá ser importante, mas tudo bem, entendo lá que você não está impossibilitado. Esse é o primeiro ponto. Segundo, você fala da questão das igrejas com o percentual de pessoas que saem. se movimentam nas igrejas. Aí eu falei que nós estamos com um problema aqui de adolescentes e jovens na igreja. Nós temos um alacuno, nós temos crianças, jr. ali, crianças e adultos e nós não temos jovens aqui na igreja, porque você tem ideia nesse sentido. É outro ponto do áudio que você faz. Aí eu complemento, por exemplo, que nós estamos com a grande evasão também de adultos na IBD e esses adultos pais não trazem seus filhos para IBD. Então, além da evasão de jovens que nós temos na igreja de uma forma geral, nós estamos com a evasão de adultos e crianças na IBD porque os pais não trazem os abençoadinhos para participar. Nós até temos um projeto com juniores adolescentes na sexta-feira mas ele tem um propósito mais evangelístico no que necessariamente de ensino semelhante a IBD. Então é por isso que nós estamos tentando a questão do PEC para que nós possamos entender a nossa cultura aqui. O que está acontecendo? A ponta a gente tentar planejar alguma coisa para que realmente venha trazer frutos aí no sentido da educação cristal. Esse é o outro ponto. Aí lá também no áudio você faça da questão do prédio, que ele tem algumas questões a serem reformadas e tal. A última vez que o pastor de Bertha mandou para mim, parece que tinha uns forvos, que tinham caído de uma sala e tudo mais e tal, mas mesmo nesse sentido aí que foi passar. Mas o pastor de Bertha é assim, ele é meio sistemático. Eu começo conversar, na hora em que eu vejo que ele bloqueia, eu não falo mais nada também, entendeu? Às vezes a resposta deve ser mais seca em algumas situações, então eu não consigo com as questões. Mas aí eu fui onde eu argumentei, eu falei assim, eu não posso ir lá, você falou da CREAL COMISSÃO pra analisar de fato como está o prédio. Então eu falei, eu não posso participar porque eu sou idealista, enquanto eles vão enxergar que não dá, eu vou enxergar que é possível. Da mesma forma, eu estou enxergando que é possível usar o seminário pra alguma situação, tá seus áudios anteriores.

