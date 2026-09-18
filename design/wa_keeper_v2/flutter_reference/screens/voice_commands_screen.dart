import 'package:flutter/material.dart';

class VoiceCommandsScreen extends StatelessWidget {
  const VoiceCommandsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('VoiceCommands')),
      body: const Center(
        child: Text('Referência visual: ver assets/mockups/reference.'),
      ),
    );
  }
}
