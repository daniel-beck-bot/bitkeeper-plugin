package hudson.plugins.bitkeeper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.Descriptor.FormException;
import hudson.scm.ChangeLogParser;
import hudson.scm.PollingResult;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.scm.SCMRevisionState;
import hudson.util.FormValidation;
import hudson.util.VersionNumber;

import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.framework.io.ByteBuffer;

public class BitKeeperSCM extends SCM {
	/**
     * Source repository URL from which we pull.
     */
    private final String parent;

    /**
     * Local name of the repository
     */
    private final String localRepository;

    /**
     * Whether we should use 'bk pull' to update the local repository
     * If not, we clean out the repo, and clone a fresh copy
     */
    private final boolean usePull;
    
    /**
     * Specifies whether pull and clone commands should run in quiet mode
     * By default, these commands print out all files that have changed.
     * Since cloning can be quite verbose, turning on quiet mode can make the console
     * output much more useful
     */
    private final boolean quiet;
    
    /** 
     * How many times to retry clone/pull operations before declaring the build a failure
     */
    private final int maxAttempts = 9;
    
    @DataBoundConstructor
    public BitKeeperSCM(String parent, String localRepository, boolean usePull, boolean quiet) {
        this.parent = parent;
        this.localRepository = localRepository;
        this.usePull = usePull;
        this.quiet = quiet;
    }

    /**
     * Gets the source repository path.
     * Either URL or local file path.
     */
    public String getParent() {
        return parent;
    }
    
    /**
     * Gets the local repository directory.
     * Must be a local file path.
     */
    public String getLocalRepository() {
        return localRepository;
    }
    
    public boolean isUsePull() {
    	return usePull;
    }

    public boolean isQuiet() {
    	return quiet;
    }

    @Override
    public boolean requiresWorkspaceForPolling() {
        return false;
    }

    @Override
	public FilePath getModuleRoot(FilePath workspace, AbstractBuild build) {
		return workspace.child(this.localRepository);
	}

    @Override
    public boolean checkout(AbstractBuild<?, ?> build, Launcher launcher,
            FilePath workspace, BuildListener listener, File changelogFile)
            throws IOException, InterruptedException {
    	
        FilePath localRepo = workspace.child(localRepository);
        if(this.usePull && localRepo.exists()) {
            pullLocalRepo(build, launcher, listener, workspace);
        } else {
        	cloneLocalRepo(build, launcher, listener, workspace);
        } 
        
        saveChangelog(build, launcher, listener, changelogFile, localRepo);

        String mostRecent = 
            this.getLatestChangeset(
                build.getEnvironment(listener), launcher, workspace,
                this.localRepository, listener
            );
        build.addAction(new BitKeeperTagAction(build, mostRecent));
        return true;
	}

    @Override
    public SCMRevisionState calcRevisionsFromBuild(AbstractBuild<?, ?> build,
            Launcher launcher, TaskListener listener) throws IOException,
            InterruptedException {
        String mostRecent = 
            this.getLatestChangeset(
                build.getEnvironment(listener), launcher, build.getWorkspace(),
                this.localRepository, listener
            );
        return new BitKeeperTagAction(build, mostRecent);
    }

	private void pullLocalRepo(AbstractBuild<?,?> build, Launcher launcher, 
			BuildListener listener, FilePath workspace) 
	throws IOException, InterruptedException, AbortException {
		FilePath localRepo = workspace.child(localRepository);
		PrintStream output = listener.getLogger();
		
    	ArrayList<String> args = new ArrayList<String>();
    	args.add(getDescriptor().getBkExe());
    	args.add("pull");
    	args.add("-u");
    	args.add("-c" + maxAttempts);
    	if(quiet) args.add("-q");
    	args.add(parent);
		if(launcher.launch().cmds(args)
		        .envs(build.getEnvironment(listener)).stdout(output).pwd(localRepo).join() != 0)
		{
		        listener.error("Failed to pull from " + parent);
		        throw new AbortException();        	
		}
		output.println("Pull completed");
	}

	private void saveChangelog(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener,
			File changelogFile, FilePath localRepo)
			throws IOException, InterruptedException, FileNotFoundException,
			AbortException {
            OutputStream changelog = null;
            Run prevBuild = build.getPreviousBuild();
            BitKeeperTagAction tagAction = 
                prevBuild == null ? null : prevBuild.getAction(BitKeeperTagAction.class);
            String recentCset = tagAction == null ? null : tagAction.getCsetkey();
            try {
                changelog = new FileOutputStream(changelogFile);
                if(recentCset == null || recentCset.equals("")) {
                    listener.error("No most recent changeset available for changelog");
                    return;
                }

                if(launcher.launch().cmds(getDescriptor().getBkExe(),
                        "changes",
                	"-v", 
                	"-r" + recentCset + "..",
                	"-d$if(:CHANGESET:){U :USER:\n$each(:C:){C (:C:)\n}$each(:TAG:){T (:TAG:)\n}}$unless(:CHANGESET:){F :GFILE:\n}")
                    .envs(build.getEnvironment(listener)).stdout(changelog).pwd(localRepo).join() != 0)
                {
                    listener.error("Failed to save changelog");
                    throw new AbortException();        	
                }
	    } finally {
                if(changelog != null)
                    changelog.close();
            }
            listener.getLogger().println("Changelog saved");
	}

	@Override
	public ChangeLogParser createChangeLogParser() {
		return new BitKeeperChangeLogParser();
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return DescriptorImpl.DESCRIPTOR;
	}
	
	private String getLatestChangeset(Map<String, String> env, Launcher launcher, 
			FilePath workspace, String repository, TaskListener listener) 
	throws IOException, InterruptedException 
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		if(launcher == null) {
		    launcher = new Launcher.LocalLauncher(listener);
		}
    	if(launcher.launch().cmds(
                getDescriptor().getBkExe(),"changes","-r+", "-d:CSETKEY:", "-D", repository)
                .envs(env).stdout(baos).pwd(workspace).join()!=0) {
    		// dump the output from bk to assist trouble-shooting.
            Util.copyStream(new ByteArrayInputStream(baos.toByteArray()),listener.getLogger());
            listener.error("Failed to check the latest changeset");
            throw new AbortException();
    	}
        // obtain the current changeset
        String rev = null;
        for( String line : Util.tokenize(new String(baos.toByteArray(), "ASCII"),"\r\n") ) {
            line = line.trim();
            rev = line;
            break;
        }
        if(rev==null) {
            Util.copyStream(new ByteArrayInputStream(baos.toByteArray()),listener.getLogger());
            listener.error("Failed to identify a revision");
            throw new AbortException();
        }

    	return rev;
	}
	
    private void cloneLocalRepo(AbstractBuild<?,?> build, Launcher launcher, 
    		TaskListener listener, FilePath workspace) 
    throws InterruptedException, IOException 
    {
        FilePath localRepo = workspace.child(localRepository);

    	ArrayList<String> args = new ArrayList<String>();
    	args.add(getDescriptor().getBkExe());
    	args.add("clone");
    	if(quiet) args.add("-q");
    	args.add(parent);
    	args.add(localRepository);
    	PrintStream output = listener.getLogger();
    	
    	int attempt = 0;
    	int result = 0;
    	do {
    		if(result != 0) {
    			Thread.sleep(30000);
    			listener.error("Retrying clone");
    			
    		}
    		localRepo.deleteRecursive();
    		result = launcher.launch().cmds(args)
    				 .envs(build.getEnvironment(listener)).stdout(output).pwd(workspace).join();
    	} while(++attempt < maxAttempts && result != 0);
    	
    	if(result != 0) {
    		listener.error("Failed to clone after " + maxAttempts + " attempts from " + this.parent);
    		throw new AbortException();
    	}
    	
    	output.println("New clone made");
    }

    public static final class DescriptorImpl extends SCMDescriptor<BitKeeperSCM> {
        @Extension
        public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

        private String bkExe;

        private DescriptorImpl() {
            super(BitKeeperSCM.class, null);
            load();
        }

        public String getDisplayName() {
            return "BitKeeper";
        }

        /**
         * Path to BitKeeper executable.
         */
        public String getBkExe() {
            if(bkExe==null) return "bk";
            return bkExe;
        }

        @Override
        public SCM newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return super.newInstance(req, formData);
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            bkExe = req.getParameter("bitkeeper.bkExe");
            save();
            return true;
        }

        public FormValidation doBkExeCheck(@QueryParameter String value) {
            return FormValidation.validateExecutable(value, new FormValidation.FileValidator() {
                @Override public FormValidation validate(File exe) {
                    ByteBuffer baos = new ByteBuffer();
                    try {
                        Hudson.getInstance().createLauncher(TaskListener.NULL).launch()
                                .cmds(getBkExe(), "version").stdout(baos).join();

                        Matcher m = VERSION_STRING.matcher(baos.toString());
                        if(m.find()) {
                            try {
                                if(new VersionNumber(m.group(1)).compareTo(V4_0_1)>=0) {
                                    return FormValidation.ok(); // right version
                                } else {
                                    return FormValidation.error("This bk is version "+m.group(1)+" but we need 4.0.1+");
                                }
                            } catch (IllegalArgumentException e) {
                                return FormValidation.warning("Jenkins can't tell if this bk is 4.0.1 or later (detected version is %s)",m.group(1));
                            }
                        }
                    } catch (IOException e) {
                        // failed
                    } catch (InterruptedException e) {
                        // failed
                    }
                    return FormValidation.error("Unable to check bk version");
                }
            });
        }

        /**
         * Pattern matcher for the version number.
         */
        private static final Pattern VERSION_STRING = Pattern.compile("BitKeeper version is bk-([0-9.]+)");

        private static final VersionNumber V4_0_1 = new VersionNumber("4.0.1");
    }

    @Override
    protected PollingResult compareRemoteRevisionWith(
            AbstractProject<?, ?> project, Launcher launcher,
            FilePath workspace, TaskListener listener, SCMRevisionState baseline)
            throws IOException, InterruptedException {
        Run lastBuild = project.getLastBuild();
        BitKeeperTagAction tagAction = 
            lastBuild == null ? null : lastBuild.getAction(BitKeeperTagAction.class);
        String recentCset = tagAction == null ? null : tagAction.getCsetkey();
        String cset = 
            this.getLatestChangeset(Collections.<String,String>emptyMap(), launcher, workspace, parent, listener);
        return (cset.equals(recentCset)) ? PollingResult.NO_CHANGES : PollingResult.SIGNIFICANT;
    }
}
