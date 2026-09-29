# Gson reflects over these locally persisted and bundled JSON model fields.
# Keep their serialized field names stable while allowing their classes and
# methods to be shrunk, optimized, and obfuscated normally.
-keepclassmembers class com.example.ai.GeneratedRoutine {
    <fields>;
}
-keepclassmembers class com.example.ai.GeneratedDay {
    <fields>;
}
-keepclassmembers class com.example.ai.GeneratedExercise {
    <fields>;
}
-keepclassmembers enum com.example.ai.GeneratedPlanSource {
    <fields>;
}
-keepclassmembers class com.example.data.SavedRoutine {
    <fields>;
}
-keepclassmembers enum com.example.data.SavedRoutineOrigin {
    <fields>;
}
-keepclassmembers class com.example.data.PlannerExercise {
    <fields>;
}
-keepclassmembers class com.example.data.WorkoutSplit {
    <fields>;
}
-keepclassmembers class com.example.ui.TrackedExercise {
    <fields>;
}
-keepclassmembers class com.example.ui.TrackedSet {
    <fields>;
}
