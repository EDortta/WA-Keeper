import 'package:flutter/material.dart';

class ScheduledMessagesScreen extends StatelessWidget {
  const ScheduledMessagesScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('ScheduledMessages')),
      body: const Center(
        child: Text('Referência visual: ver assets/mockups/reference.'),
      ),
    );
  }
}
