import 'package:flutter/material.dart';
import 'wa_colors.dart';

abstract final class WaTheme {
  static ThemeData get light => ThemeData(
        useMaterial3: true,
        brightness: Brightness.light,
        scaffoldBackgroundColor: WaColors.lightBackground,
        colorScheme: ColorScheme.fromSeed(
          seedColor: WaColors.primary,
          brightness: Brightness.light,
          primary: WaColors.primary,
          secondary: WaColors.cyan,
          surface: WaColors.lightSurface,
          error: WaColors.error,
        ),
        appBarTheme: const AppBarTheme(
          elevation: 0,
          centerTitle: false,
          backgroundColor: WaColors.lightBackground,
          foregroundColor: WaColors.lightText,
        ),
        cardTheme: CardThemeData(
          elevation: 0,
          color: WaColors.lightSurface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(18),
            side: const BorderSide(color: WaColors.lightBorder),
          ),
        ),
        inputDecorationTheme: InputDecorationTheme(
          filled: true,
          fillColor: WaColors.lightSurface,
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(14),
            borderSide: const BorderSide(color: WaColors.lightBorder),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(14),
            borderSide: const BorderSide(color: WaColors.lightBorder),
          ),
        ),
      );

  static ThemeData get dark => ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: WaColors.darkBackground,
        colorScheme: ColorScheme.fromSeed(
          seedColor: WaColors.primary,
          brightness: Brightness.dark,
          primary: WaColors.secondary,
          secondary: WaColors.cyan,
          surface: WaColors.darkSurface,
          error: WaColors.error,
        ),
        appBarTheme: const AppBarTheme(
          elevation: 0,
          centerTitle: false,
          backgroundColor: WaColors.darkBackground,
          foregroundColor: WaColors.darkText,
        ),
        cardTheme: CardThemeData(
          elevation: 0,
          color: WaColors.darkSurface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(18),
            side: const BorderSide(color: WaColors.darkBorder),
          ),
        ),
      );
}
