package grl.driver;

import static com.kuka.roboticsAPI.motionModel.BasicMotions.positionHold;
import static com.kuka.roboticsAPI.motionModel.BasicMotions.ptp;

import grl.ProcessDataManager;
import grl.StartStopSwitchUI;
import grl.TeachMode;
import grl.UpdateConfiguration;
import grl.flatbuffer.ArmState;
import grl.flatbuffer.MoveArmJointServo;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.zeromq.ZMQ;

import com.google.flatbuffers.Table;
import com.kuka.connectivity.fastRobotInterface.FRIConfiguration;
import com.kuka.connectivity.fastRobotInterface.FRIJointOverlay;
import com.kuka.connectivity.fastRobotInterface.FRISession;
import com.kuka.connectivity.motionModel.smartServo.ISmartServoRuntime;
import com.kuka.connectivity.motionModel.smartServo.ServoMotion;
import com.kuka.connectivity.motionModel.smartServo.ServoMotionJP;
import com.kuka.connectivity.motionModel.smartServo.SmartServo;
import com.kuka.roboticsAPI.applicationModel.RoboticsAPIApplication;
import com.kuka.roboticsAPI.controllerModel.Controller;
import com.kuka.roboticsAPI.controllerModel.recovery.IRecovery;
import com.kuka.roboticsAPI.deviceModel.JointPosition;
import com.kuka.roboticsAPI.deviceModel.LBR;
import com.kuka.roboticsAPI.geometricModel.CartDOF;
import com.kuka.roboticsAPI.geometricModel.LoadData;
import com.kuka.roboticsAPI.geometricModel.PhysicalObject;
import com.kuka.roboticsAPI.geometricModel.Tool;
import com.kuka.roboticsAPI.motionModel.HandGuidingMotion;
import com.kuka.roboticsAPI.motionModel.IMotionContainer;
import com.kuka.roboticsAPI.motionModel.MotionBatch;
import com.kuka.roboticsAPI.motionModel.controlModeModel.AbstractMotionControlMode;
import com.kuka.roboticsAPI.motionModel.controlModeModel.CartesianImpedanceControlMode;
import com.kuka.roboticsAPI.motionModel.controlModeModel.JointImpedanceControlMode;
import com.kuka.roboticsAPI.motionModel.controlModeModel.PositionControlMode;


/**
 * Creates a FRI Session.
 */
public class GRL_Driver extends RoboticsAPIApplication
{
	private ProcessDataManager _processDataManager = null; // Stores variables that can be modified by teach pendant in "Process Data" Menu
	private Controller _lbrController;
	private LBR _lbr;
	private StartStopSwitchUI _startStopUI = null;
	
	private SmartServo         _smartServoMotion = null;
	private ISmartServoRuntime _smartServoRuntime = null;
    // create an JointPosition Instance, to play with
    private JointPosition              _smartServoDestination = null;
	
	private grl.flatbuffer.KUKAiiwaState _currentKUKAiiwaState = null;
	private grl.flatbuffer.KUKAiiwaState _previousKUKAiiwaState = null;
	private AbstractMotionControlMode _activeMotionControlMode;
	private UpdateConfiguration _updateConfiguration;
	private IRecovery _pausedApplicationRecovery = null;
	private PhysicalObject _toolAttachedToLBR;
	private HandGuidingMotion handGuidingMotion;
	/**
	 *  gripper or other physically attached object
	 *  see "Template Data" panel in top right pane
	 *  of Sunrise Workbench. This can't be created
	 *  at runtime so we create one for you.
	 */
	private Tool    _flangeAttachment;

	@Override
	public void initialize()
	{
		_startStopUI = new StartStopSwitchUI(this);
		_processDataManager = new ProcessDataManager(this);
		_lbrController = (Controller) getContext().getControllers().toArray()[0];
		_lbr = (LBR) _lbrController.getDevices().toArray()[0];

		// TODO: fix these, right now they're useless
		//_flangeAttachment = getApplicationData().createFromTemplate("FlangeAttachment");
		//_updateConfiguration = new UpdateConfiguration(_lbr,_flangeAttachment);
		_pausedApplicationRecovery = getRecovery();

		LoadData _loadData = new LoadData();
		_loadData.setMass(_processDataManager.getEndEffectorWeight());
		_toolAttachedToLBR = new Tool("Tool", _loadData);
		_toolAttachedToLBR.attachTo(_lbr.getFlange());


		handGuidingMotion = new HandGuidingMotion();
	}

	@Override
	public void run()
	{

		ZMQ.Context context = ZMQ.context(1);

		boolean first_run = true;
		getLogger().info("ZMQ Connecting to: " + _processDataManager.get_ZMQ_MASTER_URI());
		ZMQ.Socket subscriber = context.socket(ZMQ.DEALER);
		subscriber.connect(_processDataManager.get_ZMQ_MASTER_URI());
		subscriber.setRcvHWM(1000000);

		int statesLength = 0;
		grl.flatbuffer.KUKAiiwaStates currentKUKAiiwaStates = null;
		byte [] data = null;
		ByteBuffer bb = null;

		getLogger().info("Waiting for initialization...");
		while(statesLength<1 && currentKUKAiiwaStates == null){
			if((data = subscriber.recv(ZMQ.DONTWAIT))!=null){
				bb = ByteBuffer.wrap(data);
				getLogger().info("Flatbuffer received");

				currentKUKAiiwaStates = grl.flatbuffer.KUKAiiwaStates.getRootAsKUKAiiwaStates(bb);
				statesLength = currentKUKAiiwaStates.statesLength();
			}

			if (_startStopUI.is_stopped()) {
				getLogger().info("Stopping program.");
				return;
			}
		}

		getLogger().info("States initialized...");

		// TODO: remove default start pose
		// move do default start pose
		//_toolAttachedToLBR.move(ptp(Math.toRadians(10), Math.toRadians(10), Math.toRadians(10), Math.toRadians(-90), Math.toRadians(10), Math.toRadians(10),Math.toRadians(10)));



		// Prepare ZeroMQ context and dealer
		//getArmConfiguration(configSubscriber);

		//		JointPosition initialPosition = new JointPosition(
		//				_lbr.getCurrentJointPosition());
		//SmartServo aSmartServoMotion = null;
		//
		//		// Set the motion properties to 20% of systems abilities
		//		double jointVelRel = getApplicationData().getProcessData("jointVelRel").getValue();
		//		double jointAccRel = getApplicationData().getProcessData("jointAccRel").getValue();
		//		aSmartServoMotion.setJointAccelerationRel(jointVelRel);
		//		aSmartServoMotion.setJointVelocityRel(jointAccRel);
		//		aSmartServoMotion.setTimeoutAfterGoalReach(300);

		// TODO: read from SmartServo config
		//		aSmartServoMotion.setMinimumTrajectoryExecutionTime(20e-3);

		//JointImpedanceControlMode controlMode = new JointImpedanceControlMode(7); // TODO!!
		//aSmartServoMotion.setMode(controlMode);
		//		_lbr.moveAsync(aSmartServoMotion);
		//		theSmartServoRuntime = aSmartServoMotion.getRuntime();

		// create an JointPosition Instance, to play with
		JointPosition destination = new JointPosition(
				_lbr.getJointCount());


		IMotionContainer currentMotion = null;

		boolean stop = false;
		boolean newConfig = false;

		// TODO: Let user set mode (teach/joint control from tablet as a backup!)
		//this.getApplicationData().getProcessData("DefaultMode").


		JointImpedanceControlMode controlMode2 = new JointImpedanceControlMode(7); // TODO!!
		controlMode2.setStiffnessForAllJoints(0.1);
		controlMode2.setDampingForAllJoints(0.7);

		int abort_counter = 0;
		// TODO: this teach mode is broken!
		//TeachMode tm = new TeachMode(_lbr); 
		//Thread teachModeThread = new Thread(tm); 
		// Receive Flat Buffer and Move to Position
		// TODO: add a message that we send to the driver with data log strings
		while (!stop && !_startStopUI.is_stopped()) {

			// TODO: IMPORTANT: this recv call must be made asynchronous
			boolean isRecoveryRequired = _pausedApplicationRecovery.isRecoveryRequired();

			// TODO: Allow updates via zmq and tablet
			if((data = subscriber.recv(ZMQ.DONTWAIT))!=null){
				abort_counter+=1;if (abort_counter > 1000) { getLogger().warn("Aborting!"); break; }
				bb = ByteBuffer.wrap(data);

				currentKUKAiiwaStates = grl.flatbuffer.KUKAiiwaStates.getRootAsKUKAiiwaStates(bb, currentKUKAiiwaStates);

				// TODO: this loop needs to be initialized in the right order
				// and account for runtime changes on tablet and ZMQ, then sync them
				if(currentKUKAiiwaStates.statesLength()>0) {
					// initialize the fist state
					grl.flatbuffer.KUKAiiwaState tmp = currentKUKAiiwaStates.states(0);
					if (tmp == null || tmp.armControlState() == null) {
						if (abort_counter % 100 == 0) {
							getLogger().warn("NULL ArmControlState message!");
						}
						abort_counter += 1;
						continue;
					} else {
						_previousKUKAiiwaState = _currentKUKAiiwaState;
						_currentKUKAiiwaState = tmp;
					}

					synchronized(_lbr) {
						
						if (_currentKUKAiiwaState == null) {
							getLogger().error("Missing current state message!");
							continue;
						}

						if(_previousKUKAiiwaState != null &&
								_currentKUKAiiwaState.armControlState() != _previousKUKAiiwaState.armControlState())
						{
							getLogger()
							.info("Switching mode: "
									+ ArmState.name(_currentKUKAiiwaState.armControlState().stateType()));
						}

						if(_currentKUKAiiwaState.armControlState().stateType() == grl.flatbuffer.ArmState.ShutdownArm){
							stop = true;
						}
						else if (_currentKUKAiiwaState.armControlState().stateType() == grl.flatbuffer.ArmState.MoveArmTrajectory) {
							//tm.setActive(false);
							_smartServoRuntime.stopMotion();
							if (currentMotion != null) {
								currentMotion.cancel();
							}

							grl.flatbuffer.MoveArmTrajectory maj = null;
							_currentKUKAiiwaState.armControlState().state(maj);

							for (int j = 0; j < maj.trajLength(); j++) {

								JointPosition pos = new JointPosition(_lbr.getCurrentJointPosition());

								for (int k = 0; k < destination.getAxisCount(); ++k)
								{
									//destination.set(k, maj.traj(j).position(k));
									pos.set(k, maj.traj(j).position(k));
									currentMotion = _lbr.moveAsync(ptp(pos));
								}

							}
						} else if (_currentKUKAiiwaState.armControlState().stateType() == grl.flatbuffer.ArmState.MoveArmJointServo) {
							/// TODO: this check isn't correct, if we are already in jointServo and are just updating, we don't want to cancel the current motion
							if (currentMotion != null) {
								currentMotion.cancel();
							}

							MoveArmJointServo mas = new MoveArmJointServo();
							if(_currentKUKAiiwaState.armControlState() != null) {
							_currentKUKAiiwaState.armControlState().state(mas);
							} else {
								getLogger().error("Received null armControlState in servo!");
								continue;
							
							}
							
							if(_smartServoRuntime == null || _smartServoMotion == null) {
								// make sure this is up
								// also make sure this is running
						        destination = new JointPosition(
						                _lbr.getCurrentJointPosition());
						        _smartServoMotion = new SmartServo(destination);
						        
						        // Set the motion properties to 20% of systems abilities
						        _smartServoMotion.setJointAccelerationRel(0.2);
						        _smartServoMotion.setJointVelocityRel(0.2);
						        _smartServoMotion.setMinimumTrajectoryExecutionTime(20e-3);
						        _toolAttachedToLBR.getDefaultMotionFrame().moveAsync(_smartServoMotion);

						        _smartServoRuntime = _smartServoMotion.getRuntime();
							}

							//if(_currentKUKAiiwaState.armConfiguration().commandInterface() == grl.flatbuffer.KUKAiiwaInterface.SmartServo){
								grl.flatbuffer.JointState jointState = mas.goal();

								for (int k = 0; k < destination.getAxisCount(); ++k)
								{
									destination.set(k, jointState.position(k));
								}
								
								// TODO: we need to make sure this is running, and we need to cancel the current motion
								_smartServoRuntime.setDestination(destination);

							/*} else if(_currentKUKAiiwaState.armConfiguration().commandInterface()==grl.flatbuffer.KUKAiiwaInterface.FRI){

								FRISession friSession = _updateConfiguration.get_FRISession();
								FRIJointOverlay motionOverlay = new FRIJointOverlay(friSession);

								try {
									friSession.await(10, TimeUnit.SECONDS);

									currentMotion = _lbr.moveAsync(positionHold(_activeMotionControlMode, -1, TimeUnit.SECONDS).addMotionOverlay(motionOverlay));
								} catch (TimeoutException e) {
									e.printStackTrace();
									friSession.close();
									return;
								}

							}*/

						} else if (_currentKUKAiiwaState.armControlState().stateType() == grl.flatbuffer.ArmState.StopArm) {

							_smartServoRuntime.stopMotion();
							if (currentMotion != null) {
								currentMotion.cancel();
							}
							//tm.setActive(false);

						} else if (_currentKUKAiiwaState.armControlState().stateType() == grl.flatbuffer.ArmState.TeachArm) {

							if(!first_run && (_previousKUKAiiwaState == null || _previousKUKAiiwaState.armControlState()==null)) {
								continue;
							}
							else if(first_run || _currentKUKAiiwaState.armControlState().stateType()!=_previousKUKAiiwaState.armControlState().stateType()) {

								if (currentMotion != null) {
									currentMotion.cancel();
								}

								getLogger().warn("Enabling Teach Mode (grav comp): current = " +
										_currentKUKAiiwaState.armControlState().stateType());// + ", prev = " +						_previousKUKAiiwaState.armControlState().stateType());


								currentMotion = _toolAttachedToLBR.moveAsync(positionHold(controlMode2, -1, TimeUnit.SECONDS));
								first_run = false;
							}
						} else {
							System.out.println("Unsupported Mode! stopping");
							stop = true;
						}
					}
				}
			}
		}


		// done
		subscriber.close();
		context.term();
		if (_updateConfiguration!=null && _updateConfiguration.get_FRISession() != null) {
			_updateConfiguration.get_FRISession().close();
		}
		//System.exit(1);
	}


	boolean updateConfig(grl.flatbuffer.KUKAiiwaArmConfiguration newConfig){

		if(newConfig == null) return false;

		if(_currentKUKAiiwaState.armConfiguration().controlMode()!=_previousKUKAiiwaState.armConfiguration().controlMode())
		{
			if(newConfig.controlMode()==grl.flatbuffer.EControlMode.POSITION_CONTROL_MODE){
				_activeMotionControlMode = new PositionControlMode();
			} else if(newConfig.controlMode()==grl.flatbuffer.EControlMode.CART_IMP_CONTROL_MODE){
				CartesianImpedanceControlMode cicm = new CartesianImpedanceControlMode();
				// TODO: read relevant stiffness/damping params
				//cicm.parametrize(CartDOF.X).setStiffness(stiffnessX);
				//cicm.parametrize(CartDOF.Y).setStiffness(stiffnessY);
				//cicm.parametrize(CartDOF.Z).setStiffness(stiffnessZ);

				_activeMotionControlMode = cicm;
			} else if(newConfig.controlMode()==grl.flatbuffer.EControlMode.JOINT_IMP_CONTROL_MODE){
				JointImpedanceControlMode cicm = new JointImpedanceControlMode();
				// TODO: read relevant stiffness/damping params
				//cicm.parametrize(CartDOF.X).setStiffness(stiffnessX);
				//cicm.parametrize(CartDOF.Y).setStiffness(stiffnessY);
				//cicm.parametrize(CartDOF.Z).setStiffness(stiffnessZ);

				_activeMotionControlMode = cicm;
			}

			_smartServoRuntime.changeControlModeSettings(_activeMotionControlMode);
		}



		return true;
	}

	/**
	 * main.
	 * 
	 * @param args
	 *            args
	 */
	public static void main(final String[] args)
	{
		final GRL_Driver app = new GRL_Driver();
		app.runApplication();
	}

}
