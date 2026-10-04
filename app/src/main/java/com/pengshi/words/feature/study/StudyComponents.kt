package com.pengshi.words.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pengshi.words.model.Feedback

@Composable
fun FeedbackButtons(enabled: Boolean, onAction: (StudyAction) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FeedbackButton("忘记", Feedback.AGAIN, enabled, onAction)
        FeedbackButton("困难", Feedback.HARD, enabled, onAction)
        FeedbackButton("记得", Feedback.GOOD, enabled, onAction)
        FeedbackButton("熟知", Feedback.EASY, enabled, onAction)
    }
}

@Composable
private fun RowScope.FeedbackButton(
    label: String,
    feedback: Feedback,
    enabled: Boolean,
    onAction: (StudyAction) -> Unit,
) {
    Button(
        onClick = { onAction(StudyAction.SubmitFeedback(feedback)) },
        enabled = enabled,
        modifier = Modifier.weight(1f),
    ) { Text(label) }
}
