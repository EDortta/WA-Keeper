import 'package:flutter/material.dart';
import '../widgets/keeper_card.dart';
import '../widgets/keeper_setting_tile.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Ajustes')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          KeeperCard(
            child: Column(
              children: [
                KeeperSettingTile(
                  icon: Icons.volume_up_rounded,
                  title: 'Leitura em voz alta',
                  subtitle: 'Configurar leitura em movimento',
                  onTap: () {},
                ),
                const Divider(),
                KeeperSettingTile(
                  icon: Icons.bookmark_border_rounded,
                  title: 'Retenção de mensagens',
                  onTap: () {},
                ),
                const Divider(),
                KeeperSettingTile(
                  icon: Icons.smart_toy_outlined,
                  title: 'Respostas automáticas',
                  onTap: () {},
                ),
                const Divider(),
                KeeperSettingTile(
                  icon: Icons.mic_none_rounded,
                  title: 'Comandos de voz',
                  onTap: () {},
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
