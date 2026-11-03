package com.shijie.resumehelper.application;
public class ApplicationProblem extends RuntimeException {
    private final int status;
    public ApplicationProblem(int status,String message){super(message);this.status=status;}
    public int status(){return status;}
}
