/**************************************************************************
 *
 * Copyright (C) 2018 Thorsten Falk
 *
 *        Image Analysis Lab, University of Freiburg, Germany
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 **************************************************************************/

package de.unifreiburg.unet;

import ij.IJ;
import ij.Prefs;
import ij.WindowManager;
import ij.process.ImageProcessor;
import ij.ImagePlus;
import ij.plugin.PlugIn;
import ij.gui.Plot;
import ij.gui.PlotWindow;
import ij.gui.Roi;
import ij.gui.ImageRoi;

import java.awt.Dimension;
import java.awt.BorderLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.awt.Color;
import javax.swing.JPanel;
import javax.swing.JCheckBox;
import javax.swing.GroupLayout;
import javax.swing.JScrollPane;
import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.JFormattedTextField;
import javax.swing.text.NumberFormatter;
import javax.swing.JList;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.JSplitPane;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.Icon;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.util.Vector;
import java.util.Locale;

import java.text.DecimalFormat;

import com.jcraft.jsch.Session;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;

import caffe.Caffe;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TextFormat.Parser;
import com.google.protobuf.TextFormat;

public class FinetuneWithImagePairsJob extends FinetuneJob implements PlugIn {

  private final TrainImagePairListView _trainFileList =
      new TrainImagePairListView();
  private final TrainImagePairListView _validFileList =
      new TrainImagePairListView();

  private final JPanel _trainImagesPanel = new JPanel(new BorderLayout());
  private final JPanel _validImagesPanel = new JPanel(new BorderLayout());
  private final JSplitPane _trainValidPane = new JSplitPane(
      JSplitPane.HORIZONTAL_SPLIT, _trainImagesPanel, _validImagesPanel);

  private final JCheckBox _labelsAreClassesCheckBox =
      new JCheckBox("Treat labels as classes",
                    Prefs.get("unet.finetuning.labelsAreClasses", true));

  public FinetuneWithImagePairsJob() {
    super();
  }

  public FinetuneWithImagePairsJob(JobTableModel model) {
    super(model);
  }

  @Override
  protected void createDialogElements() {

    super.createDialogElements();

    JLabel trainImagesLabel = new JLabel("Train image pairs");
    _trainImagesPanel.add(trainImagesLabel, BorderLayout.NORTH);
    JScrollPane trainScroller = new JScrollPane(_trainFileList);
    trainScroller.setMinimumSize(new Dimension(100, 50));
    _trainImagesPanel.add(trainScroller, BorderLayout.CENTER);
    JButton addTrainingSampleButton = new JButton("Add sample");
    _trainImagesPanel.add(addTrainingSampleButton, BorderLayout.SOUTH);
    addTrainingSampleButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            TrainImagePair p = TrainImagePair.selectImagePair();
            if (p != null) ((DefaultListModel<TrainImagePair>)
                            _trainFileList.getModel()).addElement(p);
          }
        });

    JLabel validImagesLabel = new JLabel("Validation image pairs");
    _validImagesPanel.add(validImagesLabel, BorderLayout.NORTH);
    JScrollPane validScroller = new JScrollPane(_validFileList);
    validScroller.setMinimumSize(new Dimension(100, 50));
    _validImagesPanel.add(validScroller, BorderLayout.CENTER);
    JButton addValidationSampleButton = new JButton("Add sample");
    _validImagesPanel.add(addValidationSampleButton, BorderLayout.SOUTH);
    addValidationSampleButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            TrainImagePair p = TrainImagePair.selectImagePair();
            if (p != null) ((DefaultListModel<TrainImagePair>)
                            _validFileList.getModel()).addElement(p);
          }
        });

    _horizontalDialogLayoutGroup.addComponent(_trainValidPane);
    _verticalDialogLayoutGroup.addComponent(_trainValidPane);

    _labelsAreClassesCheckBox.setToolTipText(
        "Check this if your labels indicate classes, otherwise they are " +
        "treated as instance labels for binary segmentation");
    _configPanel.add(_labelsAreClassesCheckBox);
  }

  @Override
  protected void finalizeDialog() {

    _trainValidPane.setDividerLocation(0.5);

    _fromImageButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            if (model() == null) return;
            if (_trainFileList.getModel().getSize() == 0) return;
            ImagePlus imp = _trainFileList.getModel().getElementAt(0).rawdata();
            model().setElementSizeUm(
                Tools.getElementSizeUmFromCalibration(
                    imp.getCalibration(), model().nDims()));
          }});

    super.finalizeDialog();
  }

  @Override
  protected boolean checkParameters() throws InterruptedException {

    progressMonitor().count("Checking parameters", 0);

    if (_trainFileList.getModel().getSize() == 0) {
      showMessage("U-Net Finetuning requires at least one training image.");
      return false;
    }

    int nTrain = _trainFileList.getModel().getSize();
    int nValid = _validFileList.getModel().getSize();
    TrainImagePair[] allImagePairs = new TrainImagePair[nTrain + nValid];
    for (int i = 0; i < nTrain; ++i)
        allImagePairs[i] = ((DefaultListModel<TrainImagePair>)
                            _trainFileList.getModel()).get(i);
    for (int i = 0; i < nValid; ++i)
        allImagePairs[nTrain + i] = ((DefaultListModel<TrainImagePair>)
                                     _validFileList.getModel()).get(i);

    for (TrainImagePair t : allImagePairs) {
      ImagePlus imp = t.rawdata();
      int nc = (imp.getType() == ImagePlus.COLOR_256 ||
                imp.getType() == ImagePlus.COLOR_RGB) ? 3 :
          imp.getNChannels();
      if (_nChannels == -1) _nChannels = nc;
      if (nc != _nChannels) {
        showMessage("U-Net Finetuning requires that all training and " +
                    "validation images have the same number of channels.");
        return false;
      }
    }

    Prefs.set("unet.finetuning.labelsAreClasses",
              _labelsAreClassesCheckBox.isSelected());

    return super.checkParameters();
  }

  @Override
  public void run(String arg) {
    start();
  }

  @Override
  public void run() {
    if (WindowManager.getImageCount() < 2) {
      IJ.error("U-Net Finetuning", "At least two images are required, one " +
               "containing the raw data and one containing the corresponding " +
               "labeled masks.");
      abort();
      return;
    }
    try
    {
      prepareParametersDialog();
      if (isInteractive() && !getParameters()) return;

      int nTrainImages = _trainFileList.getModel().getSize();
      int nValidImages = _validFileList.getModel().getSize();
      int nImages = nTrainImages + nValidImages;

      String[] trainBlobFileNames = new String[nTrainImages];
      Vector<String> validBlobFileNames = new Vector<String>();

      // Get class label information from annotations
      progressMonitor().initNewTask(
          "Searching class labels", progressMonitor().taskProgressMax(), 0);
      int maxClassLabel = 0;
      boolean labelsAreClasses = _labelsAreClassesCheckBox.isSelected();
      if (labelsAreClasses) {
        for (Object imgPair : ((DefaultListModel<TrainImagePair>)
                               _trainFileList.getModel()).toArray()) {
          ImagePlus imp = ((TrainImagePair)imgPair).rawlabels();
          if (imp.getBitDepth() == 8) {
            Byte[][] data = (Byte[][])imp.getStack().getImageArray();
            for (int i = 0; i < imp.getStack().getSize(); ++i)
                for (int j = 0; j < data[i].length; j++)
                    if (data[i][j] > maxClassLabel)
                        maxClassLabel = data[i][j];
          }
          else if (imp.getBitDepth() == 16)
          {
            Short[][] data = (Short[][])imp.getStack().getImageArray();
            for (int i = 0; i < imp.getStack().getSize(); ++i)
                for (int j = 0; j < data[i].length; j++)
                    if (data[i][j] > maxClassLabel)
                        maxClassLabel = data[i][j];
          }
        }
        if (maxClassLabel < 2) {
          IJ.error("Label images contain no valid annotations.\n" +
                   "Please make sure your labeling has the following " +
                   "format:\n" +
                   "0 - ignore, 1 - background, >1 foreground classes");
          abort();
          return;
        }
        int oldMax = maxClassLabel;
        for (Object imgPair : ((DefaultListModel<TrainImagePair>)
                               _validFileList.getModel()).toArray()) {
          ImagePlus imp = ((TrainImagePair)imgPair).rawlabels();
          if (imp.getBitDepth() == 8) {
            Byte[][] data = (Byte[][])imp.getStack().getImageArray();
            for (int i = 0; i < imp.getStack().getSize(); ++i)
                for (int j = 0; j < data[i].length; j++)
                    if (data[i][j] > maxClassLabel)
                        maxClassLabel = data[i][j];
          }
          else if (imp.getBitDepth() == 16)
          {
            Short[][] data = (Short[][])imp.getStack().getImageArray();
            for (int i = 0; i < imp.getStack().getSize(); ++i)
                for (int j = 0; j < data[i].length; j++)
                    if (data[i][j] > maxClassLabel)
                        maxClassLabel = data[i][j];
          }
        }
        if (maxClassLabel > oldMax) {
          IJ.showMessage("WARNING: Your validation set contains more classes " +
                         "than your training set. Extra classes will not be " +
                         "learnt!");
        }
      }
      else maxClassLabel = 1;

      _finetunedModel.classNames = new String[maxClassLabel + 1];
      _finetunedModel.classNames[0] = "Background";
      for (int i = 1; i <= maxClassLabel; ++i)
          _finetunedModel.classNames[i] = "Class " + i;

      // Convert and upload caffe blobs
      File outfile = null;
      if (sshSession() != null) {
        outfile = File.createTempFile(id(), ".h5");
        outfile.delete();
      }

      // Process train files
      for (int i = 0; i < nTrainImages; i++) {
        TrainImagePair t = ((DefaultListModel<TrainImagePair>)
                            _trainFileList.getModel()).get(i);
        progressMonitor().initNewTask(
            "Converting " + t.rawdata().getTitle(),
            0.05f * ((float)i + ((sshSession() == null) ? 1.0f : 0.5f)) /
            (float)nImages, 0);
        trainBlobFileNames[i] = processFolder() + id() + "_train_" + i + ".h5";
        if (sshSession() == null) outfile = new File(trainBlobFileNames[i]);
        t.saveHDF5Blob(
            labelsAreClasses ? _finetunedModel.classNames : null, outfile,
            _finetunedModel, progressMonitor());
        if (interrupted()) throw new InterruptedException();
        if (sshSession() != null) {
          progressMonitor().initNewTask(
              "Uploading " + trainBlobFileNames[i],
              0.05f * (float)(i + 1) / (float)nImages, 0);
          _createdRemoteFolders.addAll(
              new SftpFileIO(sshSession(), progressMonitor()).put(
                  outfile, trainBlobFileNames[i]));
          _createdRemoteFiles.add(trainBlobFileNames[i]);
          outfile.delete();
          if (interrupted()) throw new InterruptedException();
        }
      }

      // Process validation files
      for (int i = 0; i < nValidImages; i++) {
        TrainImagePair t = ((DefaultListModel<TrainImagePair>)
                            _validFileList.getModel()).get(i);
        progressMonitor().initNewTask(
            "Converting " + t.rawdata().getTitle(),
            0.05f * ((float)i + ((sshSession() == null) ? 1.0f : 0.5f)) /
            (float)nImages, 0);
        String fileNameStub = (sshSession() == null) ?
            processFolder() + id() + "_valid_" + i : null;
        File[] generatedFiles = t.saveHDF5TiledBlob(
            labelsAreClasses ? _finetunedModel.classNames : null, fileNameStub,
            _finetunedModel, progressMonitor());

        if (sshSession() == null)
            for (File f : generatedFiles)
                validBlobFileNames.add(f.getAbsolutePath());
        else {
          for (int j = 0; j < generatedFiles.length; j++) {
            progressMonitor().initNewTask(
                "Uploading " + generatedFiles[j],
                0.05f + 0.05f * (float)
                ((i + 0.5f * (1 + (float)(j + 1) / generatedFiles.length))) /
                (float)nImages, 1);
            String outFileName =
                processFolder() + id() + "_valid_" + i + "_" + j + ".h5";
            _createdRemoteFolders.addAll(
                new SftpFileIO(sshSession(), progressMonitor()).put(
                    generatedFiles[j], outFileName));
            _createdRemoteFiles.add(outFileName);
            validBlobFileNames.add(outFileName);
            if (interrupted()) throw new InterruptedException();
          }
        }
      }

      prepareFinetuning(trainBlobFileNames, validBlobFileNames);

      // Finetuning
      progressMonitor().initNewTask("U-Net finetuning", 1.0f, 0);
      runFinetuning();

      if (interrupted()) throw new InterruptedException();

      setReady(true);
    }
    catch (BlobException e) {
      IJ.error(id(), "Could not compute connected components:\n" + e);
      abort();
      return;
    }
    catch (IOException e) {
      IJ.error(id(), "Input/Output error:\n" + e);
      abort();
      return;
    }
    catch (NotImplementedException e) {
      IJ.error(id(), "Sorry, requested feature not implemented:\n" + e);
      abort();
      return;
    }
    catch (JSchException e) {
      IJ.error(id(), "SSH connection failed:\n" + e);
      abort();
      return;
    }
    catch (SftpException e) {
      IJ.error(id(), "SFTP file transfer failed:\n" + e);
      abort();
      return;
    }
    catch (InterruptedException e) {
      abort();
    }
  }

};
