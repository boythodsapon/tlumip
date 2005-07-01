package com.pb.tlumip.ts.odot;

 

import java.io.PrintWriter;

import java.io.FileWriter;

import java.io.IOException;


 

/**

 * Author: willison

 * Date: Sep 2, 2004

 * <p/>

 * Created by IntelliJ IDEA.

 */

public class HelloJava {

 

    public HelloJava(){}

 

    public static boolean write(String input){

        boolean success = false;

        try {

            PrintWriter writer = new PrintWriter(new FileWriter("/tmp/HelloJavaFromR.txt"));

            writer.println("This file was generated by calling Java from R");

            writer.println("Way to go, you did it");

            writer.println(input);

            writer.close();

            success = true;

        } catch (IOException e) {

            e.printStackTrace();

        }

        return success;

    }

 

    public static void main(String[] args) {

        HelloJava.write("Hi Jim");

    }

}
