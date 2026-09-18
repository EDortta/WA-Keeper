import 'package:flutter/material.dart';

class AutoReplyScreen extends StatelessWidget {
  const AutoReplyScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('AutoReply')),
      body: const Center(
        child: Text('Referência visual: ver assets/mockups/reference.'),
      ),
    );
  }
}
