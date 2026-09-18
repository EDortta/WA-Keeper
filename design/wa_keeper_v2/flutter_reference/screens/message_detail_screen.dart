import 'package:flutter/material.dart';

class MessageDetailScreen extends StatelessWidget {
  const MessageDetailScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('MessageDetail')),
      body: const Center(
        child: Text('Referência visual: ver assets/mockups/reference.'),
      ),
    );
  }
}
