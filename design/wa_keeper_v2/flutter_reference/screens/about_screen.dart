import 'package:flutter/material.dart';

class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('About')),
      body: const Center(
        child: Text('Referência visual: ver assets/mockups/reference.'),
      ),
    );
  }
}
