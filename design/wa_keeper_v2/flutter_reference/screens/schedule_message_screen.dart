import 'package:flutter/material.dart';

class ScheduleMessageScreen extends StatelessWidget {
  const ScheduleMessageScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('ScheduleMessage')),
      body: const Center(
        child: Text('Referência visual: ver assets/mockups/reference.'),
      ),
    );
  }
}
