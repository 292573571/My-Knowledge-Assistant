package com.example.workbench.advisor;

import org.springframework.stereotype.Component;

@Component
public class AnswerQualityAdvisor {

    public String improve(String answer) {
        if (answer == null || answer.isBlank()) {
            return "暂时没有可用回答。";
        }

        return answer;
    }
}
